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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@AndroidEntryPoint
class VisualEditorOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "OverlayServiceChannel"
        private const val NOTIFICATION_ID = 2026

        // Service state control
        private val _currentMode = MutableStateFlow(OverlayMode.IDLE)
        val currentMode: StateFlow<OverlayMode> = _currentMode

        fun setMode(mode: OverlayMode) {
            _currentMode.value = mode
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

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

    @Composable
    fun FloatingControlPanel() {
        val mode by currentMode.collectAsState()
        
        // Touch Drag helper using delta accumulation to reposition overlay window
        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                layoutParams.x += dragAmount.x.toInt()
                layoutParams.y += dragAmount.y.toInt()
                overlayView?.let { view ->
                    windowManager.updateViewLayout(view, layoutParams)
                }
            }
        }

        when (mode) {
            OverlayMode.IDLE -> CollapsedBubble(dragModifier)
            OverlayMode.EDITING -> ExpandedToolbar(dragModifier)
            OverlayMode.PLAYING -> PlayingProgressBar(dragModifier)
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
    fun ExpandedToolbar(modifier: Modifier = Modifier) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = modifier
                .wrapContentSize()
                .padding(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(8.dp)
            ) {
                // Drag handle
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Drag Handle",
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )

                // Actions
                ToolbarButton(Icons.Default.TouchApp, "TAP", Color(0xFF4CAF50)) {}
                ToolbarButton(Icons.Default.Timer, "HOLD", Color(0xFF2196F3)) {}
                ToolbarButton(Icons.Default.TrendingFlat, "DRAG", Color(0xFFFF9800)) {}
                ToolbarButton(Icons.Default.SwapVert, "SCROLL", Color(0xFF9C27B0)) {}
                ToolbarButton(Icons.Default.Image, "IMAGE", Color(0xFFE91E63)) {}
                ToolbarButton(Icons.Default.HourglassEmpty, "DELAY", Color(0xFF9E9E9E)) {}

                VerticalDivider(color = Color.Gray, modifier = Modifier.height(28.dp))

                // Control buttons
                ToolbarButton(Icons.Default.Save, "Save", Color.White) {
                    // Save Macro action (implemented later)
                    setMode(OverlayMode.IDLE)
                }
                ToolbarButton(Icons.Default.Close, "Close", Color.Red) {
                    setMode(OverlayMode.IDLE)
                }
            }
        }
    }

    @Composable
    fun PlayingProgressBar(modifier: Modifier = Modifier) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = modifier
                .width(200.dp)
                .padding(4.dp)
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
                        text = "Playing Macro...",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Playback",
                        tint = Color.Red,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { setMode(OverlayMode.EDITING) }
                    )
                }
                LinearProgressIndicator(
                    progress = 0.5f,
                    color = Color(0xFF4CAF50),
                    trackColor = Color.DarkGray,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Step 2/5 - Running...",
                    color = Color.LightGray,
                    fontSize = 11.sp
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
