package com.sipangkar.autoclick.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.sipangkar.autoclick.domain.model.ActionType
import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.model.MacroStep
import com.sipangkar.autoclick.domain.usecase.SaveMacroUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VisualEditorOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "OverlayServiceChannel"
        private const val NOTIFICATION_ID = 2026

        private val _currentMode = MutableStateFlow(OverlayMode.IDLE)
        val currentMode: StateFlow<OverlayMode> = _currentMode

        // Draggable Macro Steps State
        val macroSteps = mutableStateListOf<MacroStep>()
        var activeMacroName = mutableStateOf("New Macro")

        fun setMode(mode: OverlayMode) {
            _currentMode.value = mode
        }
    }

    @Inject
    lateinit var saveMacroUseCase: SaveMacroUseCase

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

        // Observe mode to adjust layout size
        serviceScope.launch {
            currentMode.collect { mode ->
                updateWindowSize(mode == OverlayMode.EDITING)
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
            // Reset position to cover whole screen
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
        overlayView?.let { view ->
            windowManager.updateViewLayout(view, layoutParams)
        }
    }

    @Composable
    fun FloatingControlPanel() {
        val mode by currentMode.collectAsState()
        
        var selectedStepForEdit by remember { mutableStateOf<MacroStep?>(null) }
        var showSaveDialog by remember { mutableStateOf(false) }

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
            // Fullscreen content for Editing mode
            if (mode == OverlayMode.EDITING) {
                // Dim screen overlay background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x11000000))
                )

                // Drag Arrows Drawing Canvas
                DragArrowsCanvas()

                // Render Action Markers
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

                // Header toolbar name
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

                // Float Toolbar Editor placed at bottom center
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
                        onSave = { showSaveDialog = true },
                        onClose = { setMode(OverlayMode.IDLE) }
                    )
                }

                // Step Settings dialog overlay
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
                            // Re-order remaining steps
                            val stepsCopy = ArrayList(macroSteps.sortedBy { it.sequenceOrder })
                            macroSteps.clear()
                            stepsCopy.forEachIndexed { idx, s ->
                                macroSteps.add(s.copy(sequenceOrder = idx + 1))
                            }
                            selectedStepForEdit = null
                        }
                    )
                }

                // Naming and Saving Dialog
                if (showSaveDialog) {
                    SaveMacroDialog(
                        onDismiss = { showSaveDialog = false },
                        onSave = { name ->
                            saveMacro(name)
                            showSaveDialog = false
                        }
                    )
                }

            } else {
                // Collapsed overlay views
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
                endY = 400f, // Upward scroll default
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
    fun ActionMarker(
        step: MacroStep,
        index: Int,
        onSelect: () -> Unit,
        onUpdate: (MacroStep) -> Unit
    ) {
        val density = LocalDensity.current
        val markerColor = when (step.actionType) {
            ActionType.TAP -> Color(0xFF4CAF50)
            ActionType.HOLD -> Color(0xFF2196F3)
            ActionType.DRAG -> Color(0xFFFF9800)
            ActionType.SCROLL -> Color(0xFF9C27B0)
            ActionType.IMAGE_DETECTION -> Color(0xFFE91E63)
            ActionType.DELAY -> Color(0xFF9E9E9E)
        }

        // Delay steps are purely timer based and do not need a draggable spatial marker
        if (step.actionType == ActionType.DELAY) return

        // Draggable start coordinate
        if (step.startX != null && step.startY != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(step.startX.toInt() - 40, step.startY.toInt() - 40) }
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(markerColor)
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(step) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onUpdate(
                                step.copy(
                                    startX = step.startX + dragAmount.x,
                                    startY = step.startY + dragAmount.y
                                )
                            )
                        }
                    }
                    .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                val label = if (step.actionType == ActionType.HOLD) "${step.sequenceOrder}⏱" else "${step.sequenceOrder}"
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Draggable end coordinate (for drag/scroll)
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
                    .pointerInput(step) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onUpdate(
                                step.copy(
                                    endX = step.endX + dragAmount.x,
                                    endY = step.endY + dragAmount.y
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
                    
                    // Draw path line
                    drawLine(
                        color = color,
                        start = Offset(step.startX, step.startY),
                        end = Offset(step.endX, step.endY),
                        strokeWidth = 3.dp.toPx()
                    )

                    // Draw arrowhead at end coordinate pointing towards end position
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

                    // Delay config
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

                    // Duration config (if applicable)
                    if (step.actionType == ActionType.HOLD || 
                        step.actionType == ActionType.DRAG || 
                        step.actionType == ActionType.SCROLL) {
                        
                        Column {
                            val label = if (step.actionType == ActionType.HOLD) "Hold duration" else "Drag duration"
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
                        colors = TextFieldDefaults.textFieldColors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            containerColor = Color(0xFF333333)
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
                .background(Color(0xFF212121))
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
        onSave: () -> Unit,
        onClose: () -> Unit
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
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
                ToolbarButton(Icons.Default.HourglassEmpty, "DELAY", Color(0xFF9E9E9E)) { onAddDelay() }

                VerticalDivider(color = Color.DarkGray, modifier = Modifier.height(28.dp))

                ToolbarButton(Icons.Default.Save, "Save", Color.White) { onSave() }
                ToolbarButton(Icons.Default.Close, "Close", Color.Red) { onClose() }
            }
        }
    }

    @Composable
    fun PlayingProgressBar(modifier: Modifier = Modifier) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
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
                    Text(
                        text = "Playing...",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.Red,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { setMode(OverlayMode.EDITING) }
                    )
                }
                LinearProgressIndicator(
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
                .background(Color(0xFF2D2D2D))
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
