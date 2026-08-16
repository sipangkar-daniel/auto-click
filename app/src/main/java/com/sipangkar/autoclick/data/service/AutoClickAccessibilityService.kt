package com.sipangkar.autoclick.data.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        _serviceConnected.value = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No action needed for events
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        _serviceConnected.value = false
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service Destroyed")
        _serviceConnected.value = false
        instance = null
    }

    /**
     * Performs a tap gesture at the specified coordinates.
     */
    fun performClick(x: Float, y: Float, callback: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGestureInternal(stroke, callback)
    }

    /**
     * Performs a hold gesture at the specified coordinates for a given duration.
     */
    fun performHold(x: Float, y: Float, duration: Long, callback: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGestureInternal(stroke, callback)
    }

    /**
     * Performs a drag/swipe gesture from start to end coordinates.
     */
    fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long, callback: () -> Unit) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGestureInternal(stroke, callback)
    }

    /**
     * Captures a screenshot of the current screen.
     * Uses takeScreenshot API for Android 11+ and copies the hardware bitmap to a software-backed bitmap
     * for OpenCV compatibility.
     */
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mainExecutor = mainExecutor
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                @RequiresApi(Build.VERSION_CODES.R)
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        
                        if (hardwareBitmap != null) {
                            // Copy to software config because OpenCV/Android UI cropping cannot process hardware bitmaps directly
                            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap.recycle()
                            callback(softwareBitmap)
                        } else {
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Failed to capture screenshot, error code: $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            Log.e(TAG, "Screenshot capture is not supported on Android versions below 11 (API 30)")
            callback(null)
        }
    }

    private fun dispatchGestureInternal(stroke: GestureDescription.StrokeDescription, callback: () -> Unit) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback()
            }
        }, null)
    }

    companion object {
        private const val TAG = "AutoClickService"
        
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        private val _serviceConnected = MutableStateFlow(false)
        val serviceConnected: StateFlow<Boolean> = _serviceConnected
    }
}
