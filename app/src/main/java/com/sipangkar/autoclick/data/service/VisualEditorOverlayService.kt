package com.sipangkar.autoclick.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sipangkar.autoclick.data.engine.PlaybackEngine
import com.sipangkar.autoclick.domain.model.ActionType
import com.sipangkar.autoclick.domain.model.DetectionType
import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.model.MacroStep
import com.sipangkar.autoclick.domain.model.TimeoutAction
import com.sipangkar.autoclick.domain.usecase.SaveMacroUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class VisualEditorOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "OverlayServiceChannel"
        private const val NOTIFICATION_ID = 2026

        private val _currentMode = MutableStateFlow(OverlayMode.IDLE)
        val currentMode: StateFlow<OverlayMode> = _currentMode

        val macroSteps = mutableStateListOf<MacroStep>()
        var activeMacroName = mutableStateOf("New Macro")
        val activeMacroState = mutableStateOf<Macro?>(null)
        var isTryingFlow = false

        fun setMode(mode: OverlayMode) {
            _currentMode.value = mode
        }
    }

    @Inject
    lateinit var saveMacroUseCase: SaveMacroUseCase

    @Inject
    lateinit var playbackEngine: PlaybackEngine

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Image Setup flows
    private val imageSetupState = mutableStateOf(ImageDetectionSetupState.NONE)
    private val capturedBitmapState = mutableStateOf<Bitmap?>(null)
    private val croppedImagePathState = mutableStateOf<String?>(null)

    // ROI bounding box in local layout coordinates
    private val roiLeft = mutableStateOf(100f)
    private val roiTop = mutableStateOf(200f)
    private val roiRight = mutableStateOf(500f)
    private val roiBottom = mutableStateOf(500f)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Overlay Service Created")
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundService()
        setupOverlayWindow()

        serviceScope.launch {
            currentMode.collect { mode ->
                updateWindowSize(mode == OverlayMode.EDITING)
                if (mode == OverlayMode.PLAYING) {
                    val macro = activeMacroState.value
                    if (macro != null) {
                        playbackEngine.startPlayback(macro, serviceScope) {
                            if (isTryingFlow) {
                                isTryingFlow = false
                                setMode(OverlayMode.EDITING)
                            } else {
                                setMode(OverlayMode.IDLE)
                            }
                        }
                    } else {
                        setMode(OverlayMode.IDLE)
                    }
                } else {
                    playbackEngine.stopPlayback()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Macro Auto Click Control Panel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Macro Auto Click")
            .setContentText("Overlay control panel is active")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayWindow() {
        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VisualEditorOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VisualEditorOverlayService)
            setViewTreeViewModelStoreOwner(this@VisualEditorOverlayService)

            setContent {
                MaterialTheme {
                    FloatingControlPanel()
                }
            }
        }

        windowManager.addView(overlayView, layoutParams)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun updateWindowSize(isFullscreen: Boolean) {
        if (isFullscreen) {
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            layoutParams.x = 0
            layoutParams.y = 0
        } else {
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            layoutParams.x = 100
            layoutParams.y = 300
        }
    }

    private fun performClickThrough(x: Float, y: Float) {
        val view = overlayView ?: return
        
        // Temporarily make overlay window untouchable
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(view, layoutParams)

        // Trigger simulated tap
        AutoClickAccessibilityService.instance?.performClick(x, y) {
            // Restore touchable flag after click finishes
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(view, layoutParams)
        }
    }

    @Composable
    fun FloatingControlPanel() {
        val mode by currentMode.collectAsState()
        
        var selectedStepForEdit by remember { mutableStateOf<MacroStep?>(null) }
        var showSaveDialog by remember { mutableStateOf(false) }
        val setupState by imageSetupState
        val capturedBitmap by capturedBitmapState

        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                if (mode != OverlayMode.EDITING) {
                    layoutParams.x += dragAmount.x.toInt()
                    layoutParams.y += dragAmount.y.toInt()
                    overlayView?.let { view ->
                        windowManager.updateViewLayout(view, layoutParams)
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (mode == OverlayMode.EDITING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x11000000))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                performClickThrough(offset.x, offset.y)
                            }
                        }
                )

                DragArrowsCanvas()

                macroSteps.forEachIndexed { index, step ->
                    ActionMarker(
                        step = step,
                        index = index,
                        onSelect = { selectedStepForEdit = step },
                        onUpdate = { updatedStep ->
                            val listIndex = macroSteps.indexOfFirst { it.sequenceOrder == updatedStep.sequenceOrder }
                            if (listIndex != -1) {
                                macroSteps[listIndex] = updatedStep
                            }
                        }
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC1E1E1E)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                ) {
                    Text(
                        text = "Editing: ${activeMacroName.value}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                ) {
                    ExpandedToolbar(
                        onAddTap = { addStep(ActionType.TAP) },
                        onAddHold = { addStep(ActionType.HOLD) },
                        onAddDrag = { addStep(ActionType.DRAG) },
                        onAddScroll = { addStep(ActionType.SCROLL) },
                        onAddDelay = { addStep(ActionType.DELAY) },
                        onAddImage = { imageSetupState.value = ImageDetectionSetupState.SELECT_SOURCE },
                        onTryFlow = {
                            isTryingFlow = true
                            val tempMacro = Macro(
                                name = activeMacroName.value,
                                steps = ArrayList(macroSteps)
                            )
                            activeMacroState.value = tempMacro
                            setMode(OverlayMode.PLAYING)
                        },
                        onSave = { showSaveDialog = true },
                        onClose = { setMode(OverlayMode.IDLE) }
                    )
                }

                selectedStepForEdit?.let { step ->
                    StepSettingsDialog(
                        step = step,
                        onDismiss = { selectedStepForEdit = null },
                        onSave = { updatedStep ->
                            val listIndex = macroSteps.indexOfFirst { it.sequenceOrder == updatedStep.sequenceOrder }
                            if (listIndex != -1) {
                                macroSteps[listIndex] = updatedStep
                            }
                            selectedStepForEdit = null
                        },
                        onDelete = {
                            macroSteps.remove(step)
                            val stepsCopy = ArrayList(macroSteps.sortedBy { it.sequenceOrder })
                            macroSteps.clear()
                            stepsCopy.forEachIndexed { idx, s ->
                                macroSteps.add(s.copy(sequenceOrder = idx + 1))
                            }
                            selectedStepForEdit = null
                        }
                    )
                }

                if (showSaveDialog) {
                    SaveMacroDialog(
                        onDismiss = { showSaveDialog = false },
                        onSave = { name ->
                            saveMacro(name)
                            showSaveDialog = false
                        }
                    )
                }

                if (setupState == ImageDetectionSetupState.SELECT_SOURCE) {
                    SelectImageSourceDialog(
                        onDismiss = { imageSetupState.value = ImageDetectionSetupState.NONE },
                        onLiveCapture = { triggerLiveCapture() }
                    )
                }

                if (setupState == ImageDetectionSetupState.FREEZE_CROP && capturedBitmap != null) {
                    CropToolOverlay(
                        bitmap = capturedBitmap!!,
                        onDismiss = { imageSetupState.value = ImageDetectionSetupState.NONE },
                        onCropped = { path ->
                            croppedImagePathState.value = path
                            imageSetupState.value = ImageDetectionSetupState.SETUP_ROI
                        }
                    )
                }

                if (setupState == ImageDetectionSetupState.SETUP_ROI) {
                    RoiSelectorOverlay(
                        onDismiss = { imageSetupState.value = ImageDetectionSetupState.NONE },
                        onConfirm = { left, top, right, bottom ->
                            roiLeft.value = left
                            roiTop.value = top
                            roiRight.value = right
                            roiBottom.value = bottom
                            imageSetupState.value = ImageDetectionSetupState.SETUP_PARAMS
                        }
                    )
                }

                if (setupState == ImageDetectionSetupState.SETUP_PARAMS) {
                    ImageParamsDialog(
                        onDismiss = { imageSetupState.value = ImageDetectionSetupState.NONE },
                        onSave = { type, threshold, timeout, action, offset ->
                            saveImageStep(type, threshold, timeout, action, offset)
                        }
                    )
                }

            } else {
                Box(
                    modifier = Modifier.wrapContentSize()
                ) {
                    if (mode == OverlayMode.IDLE) {
                        CollapsedBubble(dragModifier)
                    } else if (mode == OverlayMode.PLAYING) {
                        PlayingProgressBar(dragModifier)
                    }
                }
            }
        }
    }

    private fun addStep(type: ActionType) {
        val nextSeq = macroSteps.size + 1
        val newStep = when (type) {
            ActionType.TAP -> MacroStep(
                sequenceOrder = nextSeq,
                actionType = type,
                startX = 300f,
                startY = 500f,
                delayAfter = 1000L
            )
            ActionType.HOLD -> MacroStep(
                sequenceOrder = nextSeq,
                actionType = type,
                startX = 300f,
                startY = 500f,
                duration = 2000L,
                delayAfter = 1000L
            )
            ActionType.DRAG -> MacroStep(
                sequenceOrder = nextSeq,
                actionType = type,
                startX = 200f,
                startY = 600f,
                endX = 500f,
                endY = 600f,
                duration = 500L,
                delayAfter = 1000L
            )
            ActionType.SCROLL -> MacroStep(
                sequenceOrder = nextSeq,
                actionType = type,
                startX = 300f,
                startY = 800f,
                endX = 300f,
                endY = 400f,
                duration = 800L,
                delayAfter = 1000L
            )
            ActionType.DELAY -> MacroStep(
                sequenceOrder = nextSeq,
                actionType = type,
                delayAfter = 2000L
            )
            else -> MacroStep(sequenceOrder = nextSeq, actionType = type)
        }
        macroSteps.add(newStep)
    }

    private fun triggerLiveCapture() {
        overlayView?.visibility = View.GONE
        serviceScope.launch {
            kotlinx.coroutines.delay(150L)
            AutoClickAccessibilityService.instance?.captureScreen { bitmap ->
                overlayView?.visibility = View.VISIBLE
                if (bitmap != null) {
                    capturedBitmapState.value = bitmap
                    imageSetupState.value = ImageDetectionSetupState.FREEZE_CROP
                } else {
                    Log.e(TAG, "Screen capture returned null")
                    imageSetupState.value = ImageDetectionSetupState.NONE
                }
            }
        }
    }

    private fun saveImageStep(
        type: DetectionType,
        threshold: Float,
        timeout: Long,
        action: TimeoutAction,
        offset: String?
    ) {
        val density = resources.displayMetrics.density
        val physX = (roiLeft.value * density).toInt()
        val physY = (roiTop.value * density).toInt()
        val physW = ((roiRight.value - roiLeft.value) * density).toInt()
        val physH = ((roiBottom.value - roiTop.value) * density).toInt()

        val nextSeq = macroSteps.size + 1
        val newStep = MacroStep(
            sequenceOrder = nextSeq,
            actionType = ActionType.IMAGE_DETECTION,
            templateImagePath = croppedImagePathState.value,
            roiX = physX,
            roiY = physY,
            roiWidth = physW,
            roiHeight = physH,
            threshold = threshold,
            timeout = timeout,
            detectionType = type,
            timeoutAction = action,
            clickOffset = offset,
            startX = roiLeft.value + (roiRight.value - roiLeft.value) / 2,
            startY = roiTop.value + (roiBottom.value - roiTop.value) / 2
        )
        macroSteps.add(newStep)
        imageSetupState.value = ImageDetectionSetupState.NONE
    }

    private fun saveMacro(name: String) {
        val macro = Macro(
            name = name,
            steps = ArrayList(macroSteps)
        )
        serviceScope.launch {
            try {
                saveMacroUseCase(macro)
                Log.d(TAG, "Macro saved: $name")
                setMode(OverlayMode.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save macro", e)
            }
        }
    }

    @Composable
    fun SelectImageSourceDialog(
        onDismiss: () -> Unit,
        onLiveCapture: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x55000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                modifier = Modifier
                    .width(320.dp)
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Image Detection - Template Source",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Button(
                        onClick = { onLiveCapture() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Capture Screen Now")
                    }

                    OutlinedButton(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    @Composable
    fun CropToolOverlay(
        bitmap: Bitmap,
        onDismiss: () -> Unit,
        onCropped: (String) -> Unit
    ) {
        var cropLeft by remember { mutableStateOf(150f) }
        var cropTop by remember { mutableStateOf(300f) }
        var cropRight by remember { mutableStateOf(450f) }
        var cropBottom by remember { mutableStateOf(600f) }

        val density = LocalDensity.current.density

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured Screen",
                modifier = Modifier.fillMaxSize()
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.5f)
                )
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(cropLeft.toInt(), cropTop.toInt()) }
                    .size(
                        width = ((cropRight - cropLeft) / density).dp,
                        height = ((cropBottom - cropTop) / density).dp
                    )
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            cropLeft += dragAmount.x
                            cropRight += dragAmount.x
                            cropTop += dragAmount.y
                            cropBottom += dragAmount.y
                        }
                    }
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(cropRight.toInt() - 24, cropBottom.toInt() - 24) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color.Black, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            cropRight = (cropRight + dragAmount.x).coerceAtLeast(cropLeft + 50f)
                            cropBottom = (cropBottom + dragAmount.y).coerceAtLeast(cropTop + 50f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD1E1E1E)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Crop Template Box", color = Color.White, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            val cropped = cropBitmap(bitmap, cropLeft * density, cropTop * density, cropRight * density, cropBottom * density)
                            if (cropped != null) {
                                val path = saveBitmapToInternalStorage(cropped)
                                if (path != null) {
                                    onCropped(path)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Crop & OK")
                    }
                    Button(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    private fun cropBitmap(original: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap? {
        val x = left.coerceIn(0f, original.width.toFloat()).toInt()
        val y = top.coerceIn(0f, original.height.toFloat()).toInt()
        val width = (right - left).coerceIn(1f, (original.width - x).toFloat()).toInt()
        val height = (bottom - top).coerceIn(1f, (original.height - y).toFloat()).toInt()
        return try {
            Bitmap.createBitmap(original, x, y, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed: $left, $top, $right, $bottom", e)
            null
        }
    }

    private fun saveBitmapToInternalStorage(bitmap: Bitmap): String? {
        val file = File(filesDir, "template_${System.currentTimeMillis()}.png")
        return try {
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            null
        }
    }

    @Composable
    fun RoiSelectorOverlay(
        onDismiss: () -> Unit,
        onConfirm: (Float, Float, Float, Float) -> Unit
    ) {
        var boxLeft by remember { mutableStateOf(100f) }
        var boxTop by remember { mutableStateOf(200f) }
        var boxRight by remember { mutableStateOf(600f) }
        var boxBottom by remember { mutableStateOf(500f) }

        val density = LocalDensity.current.density

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(boxLeft.toInt(), boxTop.toInt()) }
                    .size(
                        width = ((boxRight - boxLeft) / density).dp,
                        height = ((boxBottom - boxTop) / density).dp
                    )
                    .border(2.dp, Color(0xFFE91E63), RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            boxLeft += dragAmount.x
                            boxRight += dragAmount.x
                            boxTop += dragAmount.y
                            boxBottom += dragAmount.y
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x1AE91E63)),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = "🔍 Area Pencarian (ROI)",
                        color = Color(0xFFE91E63),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(boxRight.toInt() - 24, boxBottom.toInt() - 24) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE91E63))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            boxRight = (boxRight + dragAmount.x).coerceAtLeast(boxLeft + 50f)
                            boxBottom = (boxBottom + dragAmount.y).coerceAtLeast(boxTop + 50f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD1E1E1E)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pilih Area Pencarian", color = Color.White, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onConfirm(boxLeft, boxTop, boxRight, boxBottom) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                    ) {
                        Text("Confirm ROI")
                    }
                    Button(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ImageParamsDialog(
        onDismiss: () -> Unit,
        onSave: (DetectionType, Float, Long, TimeoutAction, String?) -> Unit
    ) {
        var detectionType by remember { mutableStateOf(DetectionType.WAIT_UNTIL_APPEAR) }
        var threshold by remember { mutableStateOf(0.85f) }
        var timeoutVal by remember { mutableStateOf(5000f) }
        var timeoutAction by remember { mutableStateOf(TimeoutAction.STOP) }
        var offsetX by remember { mutableStateOf("0") }
        var offsetY by remember { mutableStateOf("0") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                modifier = Modifier
                    .width(340.dp)
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Image Detection Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Detection Type:", color = Color.LightGray, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetectionTypeButton(DetectionType.WAIT_UNTIL_APPEAR, "Appear", detectionType == DetectionType.WAIT_UNTIL_APPEAR) { detectionType = it }
                            DetectionTypeButton(DetectionType.WAIT_UNTIL_DISAPPEAR, "Disappear", detectionType == DetectionType.WAIT_UNTIL_DISAPPEAR) { detectionType = it }
                            DetectionTypeButton(DetectionType.CLICK_ON_APPEAR, "Click On", detectionType == DetectionType.CLICK_ON_APPEAR) { detectionType = it }
                        }
                    }

                    Column {
                        Text("Accuracy (Threshold): ${(threshold * 100).toInt()}%", color = Color.LightGray, fontSize = 12.sp)
                        Slider(
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 0.70f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFE91E63), activeTrackColor = Color(0xFFE91E63))
                        )
                    }

                    Column {
                        Text("Timeout: ${(timeoutVal / 1000f).toInt()}s", color = Color.LightGray, fontSize = 12.sp)
                        Slider(
                            value = timeoutVal,
                            onValueChange = { timeoutVal = it },
                            valueRange = 1000f..30000f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFE91E63), activeTrackColor = Color(0xFFE91E63))
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("If Timeout:", color = Color.LightGray, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = timeoutAction == TimeoutAction.STOP, onClick = { timeoutAction = TimeoutAction.STOP })
                            Text("Stop", color = Color.White, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = timeoutAction == TimeoutAction.SKIP, onClick = { timeoutAction = TimeoutAction.SKIP })
                            Text("Skip", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    if (detectionType == DetectionType.CLICK_ON_APPEAR) {
                        Column {
                            Text("Click Offset (X, Y px):", color = Color.LightGray, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(
                                    value = offsetX,
                                    onValueChange = { offsetX = it },
                                    label = { Text("X") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF333333),
                                        unfocusedContainerColor = Color(0xFF333333)
                                    )
                                )
                                TextField(
                                    value = offsetY,
                                    onValueChange = { offsetY = it },
                                    label = { Text("Y") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF333333),
                                        unfocusedContainerColor = Color(0xFF333333)
                                    )
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { onDismiss() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                val offset = if (detectionType == DetectionType.CLICK_ON_APPEAR) "${offsetX.toIntOrNull() ?: 0},${offsetY.toIntOrNull() ?: 0}" else null
                                onSave(detectionType, threshold, timeoutVal.toLong(), timeoutAction, offset)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DetectionTypeButton(
        type: DetectionType,
        label: String,
        selected: Boolean,
        onClick: (DetectionType) -> Unit
    ) {
        val containerColor = if (selected) Color(0xFFE91E63) else Color(0xFF333333)
        val contentColor = Color.White
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .clickable { onClick(type) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun ActionMarker(
        step: MacroStep,
        index: Int,
        onSelect: () -> Unit,
        onUpdate: (MacroStep) -> Unit
    ) {
        val currentStep by rememberUpdatedState(step)
        val markerColor = when (step.actionType) {
            ActionType.TAP -> Color(0xFF4CAF50)
            ActionType.HOLD -> Color(0xFF2196F3)
            ActionType.DRAG -> Color(0xFFFF9800)
            ActionType.SCROLL -> Color(0xFF9C27B0)
            ActionType.IMAGE_DETECTION -> Color(0xFFE91E63)
            ActionType.DELAY -> Color(0xFF9E9E9E)
        }

        if (step.actionType == ActionType.DELAY) return

        if (step.startX != null && step.startY != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(step.startX.toInt() - 40, step.startY.toInt() - 40) }
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(markerColor.copy(alpha = 0.8f))
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val s = currentStep
                            onUpdate(
                                s.copy(
                                    startX = (s.startX ?: 0f) + dragAmount.x,
                                    startY = (s.startY ?: 0f) + dragAmount.y
                                )
                            )
                        }
                    }
                    .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                val textLabel = when (step.actionType) {
                    ActionType.HOLD -> "${step.sequenceOrder}⏱"
                    ActionType.IMAGE_DETECTION -> "${step.sequenceOrder}🖼"
                    else -> "${step.sequenceOrder}"
                }
                Text(
                    text = textLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        if ((step.actionType == ActionType.DRAG || step.actionType == ActionType.SCROLL) &&
            step.endX != null && step.endY != null
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(step.endX.toInt() - 40, step.endY.toInt() - 40) }
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(markerColor.copy(alpha = 0.8f))
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val s = currentStep
                            onUpdate(
                                s.copy(
                                    endX = (s.endX ?: 0f) + dragAmount.x,
                                    endY = (s.endY ?: 0f) + dragAmount.y
                                )
                            )
                        }
                    }
                    .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${step.sequenceOrder}B",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    fun DragArrowsCanvas() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            macroSteps.forEach { step ->
                if ((step.actionType == ActionType.DRAG || step.actionType == ActionType.SCROLL) &&
                    step.startX != null && step.startY != null &&
                    step.endX != null && step.endY != null
                ) {
                    val color = if (step.actionType == ActionType.DRAG) Color(0xFFFF9800) else Color(0xFF9C27B0)
                    
                    drawLine(
                        color = color,
                        start = Offset(step.startX, step.startY),
                        end = Offset(step.endX, step.endY),
                        strokeWidth = 3.dp.toPx()
                    )

                    val dx = step.endX - step.startX
                    val dy = step.endY - step.startY
                    val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                    val arrowLength = 20.dp.toPx()
                    val arrowAngle = Math.PI / 6
                    
                    val arrow1X = step.endX - arrowLength * Math.cos(angle - arrowAngle)
                    val arrow1Y = step.endY - arrowLength * Math.sin(angle - arrowAngle)
                    val arrow2X = step.endX - arrowLength * Math.cos(angle + arrowAngle)
                    val arrow2Y = step.endY - arrowLength * Math.sin(angle + arrowAngle)

                    val arrowPath = Path().apply {
                        moveTo(step.endX, step.endY)
                        lineTo(arrow1X.toFloat(), arrow1Y.toFloat())
                        lineTo(arrow2X.toFloat(), arrow2Y.toFloat())
                        close()
                    }
                    drawPath(arrowPath, color = color)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun StepSettingsDialog(
        step: MacroStep,
        onDismiss: () -> Unit,
        onSave: (MacroStep) -> Unit,
        onDelete: () -> Unit
    ) {
        var delayVal by remember { mutableStateOf(step.delayAfter.toFloat()) }
        var durationVal by remember { mutableStateOf((step.duration ?: 1000L).toFloat()) }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                modifier = Modifier
                    .width(320.dp)
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Config Step #${step.sequenceOrder} - ${step.actionType.name}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Column {
                        Text(
                            text = "Delay after step: ${delayVal.toInt()}ms",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = delayVal,
                            onValueChange = { delayVal = it },
                            valueRange = 0f..5000f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF4CAF50),
                                activeTrackColor = Color(0xFF4CAF50)
                            )
                        )
                    }

                    if (step.actionType == ActionType.HOLD || 
                        step.actionType == ActionType.DRAG || 
                        step.actionType == ActionType.SCROLL) {
                        
                        Column {
                            val label = if (step.actionType == ActionType.HOLD) "Hold duration" else "Drag/Scroll duration"
                            Text(
                                text = "$label: ${String.format("%.1f", durationVal / 1000f)}s",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Slider(
                                value = durationVal,
                                onValueChange = { durationVal = it },
                                valueRange = 100f..10000f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF2196F3),
                                    activeTrackColor = Color(0xFF2196F3)
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onDelete() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Delete")
                        }
                        Button(
                            onClick = {
                                onSave(
                                    step.copy(
                                        delayAfter = delayVal.toLong(),
                                        duration = if (step.duration != null) durationVal.toLong() else null
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SaveMacroDialog(
        onDismiss: () -> Unit,
        onSave: (String) -> Unit
    ) {
        var nameText by remember { mutableStateOf("") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                modifier = Modifier
                    .width(300.dp)
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Save Macro",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    TextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        placeholder = { Text("farming_v1") },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF333333),
                            unfocusedContainerColor = Color(0xFF333333)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onDismiss() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { if (nameText.isNotBlank()) onSave(nameText) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(1f),
                            enabled = nameText.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun CollapsedBubble(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xCC212121))
                .clickable { setMode(OverlayMode.EDITING) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Expand",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }

    @Composable
    fun ExpandedToolbar(
        onAddTap: () -> Unit,
        onAddHold: () -> Unit,
        onAddDrag: () -> Unit,
        onAddScroll: () -> Unit,
        onAddDelay: () -> Unit,
        onAddImage: () -> Unit,
        onTryFlow: () -> Unit,
        onSave: () -> Unit,
        onClose: () -> Unit
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(8.dp)
            ) {
                ToolbarButton(Icons.Default.TouchApp, "TAP", Color(0xFF4CAF50)) { onAddTap() }
                ToolbarButton(Icons.Default.Timer, "HOLD", Color(0xFF2196F3)) { onAddHold() }
                ToolbarButton(Icons.Default.TrendingFlat, "DRAG", Color(0xFFFF9800)) { onAddDrag() }
                ToolbarButton(Icons.Default.SwapVert, "SCROLL", Color(0xFF9C27B0)) { onAddScroll() }
                ToolbarButton(Icons.Default.Image, "IMAGE", Color(0xFFE91E63)) { onAddImage() }
                ToolbarButton(Icons.Default.HourglassEmpty, "DELAY", Color(0xFF9E9E9E)) { onAddDelay() }

                VerticalDivider(color = Color.DarkGray, modifier = Modifier.height(28.dp))

                ToolbarButton(Icons.Default.PlayArrow, "Try Flow", Color(0xFF4CAF50)) { onTryFlow() }
                ToolbarButton(Icons.Default.Save, "Save", Color.White) { onSave() }
                ToolbarButton(Icons.Default.Close, "Close", Color.Red) { onClose() }
            }
        }
    }

    @Composable
    fun PlayingProgressBar(modifier: Modifier = Modifier) {
        val currentStep by playbackEngine.currentStep.collectAsState()
        val totalSteps = activeMacroState.value?.steps?.size ?: 1
        val progress = if (currentStep != null) currentStep!!.toFloat() / totalSteps else 0f

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = modifier.width(200.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val stepLabel = if (currentStep != null) "Step $currentStep / $totalSteps" else "Playing..."
                    Text(
                        text = stepLabel,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.Red,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { setMode(OverlayMode.IDLE) }
                    )
                }
                LinearProgressIndicator(
                    progress = progress,
                    color = Color(0xFF4CAF50),
                    trackColor = Color.DarkGray,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    fun ToolbarButton(
        icon: ImageVector,
        contentDescription: String,
        tint: Color,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xCC2D2D2D))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Overlay Service Destroyed")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
    }
}

enum class OverlayMode {
    IDLE,
    EDITING,
    PLAYING
}

enum class ImageDetectionSetupState {
    NONE,
    SELECT_SOURCE,
    FREEZE_CROP,
    SETUP_ROI,
    SETUP_PARAMS
}
