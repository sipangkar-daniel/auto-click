package com.sipangkar.autoclick.data.engine

import android.graphics.Bitmap
import android.util.Log
import com.sipangkar.autoclick.data.matcher.OpenCVTemplateMatcher
import com.sipangkar.autoclick.data.service.AutoClickAccessibilityService
import com.sipangkar.autoclick.domain.model.ActionType
import com.sipangkar.autoclick.domain.model.DetectionType
import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.model.MacroStep
import com.sipangkar.autoclick.domain.model.TimeoutAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PlaybackEngine @Inject constructor(
    private val openCVTemplateMatcher: OpenCVTemplateMatcher
) {
    companion object {
        private const val TAG = "PlaybackEngine"
    }

    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentStep = MutableStateFlow<Int?>(null)
    val currentStep: StateFlow<Int?> = _currentStep

    fun startPlayback(macro: Macro, scope: CoroutineScope, onFinished: () -> Unit) {
        if (_isPlaying.value) {
            Log.w(TAG, "Playback already running")
            return
        }

        playbackJob = scope.launch(Dispatchers.Default) {
            _isPlaying.value = true
            Log.d(TAG, "Starting playback of macro: ${macro.name}")
            
            try {
                val loops = if (macro.loopCount <= 0) 1 else macro.loopCount
                for (loop in 0 until loops) {
                    if (!isActive) break
                    Log.d(TAG, "Starting loop ${loop + 1}/$loops")

                    for (step in macro.steps.sortedBy { it.sequenceOrder }) {
                        if (!isActive) break
                        _currentStep.value = step.sequenceOrder
                        Log.d(TAG, "Executing step ${step.sequenceOrder}: ${step.actionType}")

                        val success = executeStep(step)
                        if (!success) {
                            Log.e(TAG, "Step ${step.sequenceOrder} failed. Stopping playback.")
                            break
                        }

                        if (step.delayAfter > 0) {
                            delay(step.delayAfter)
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Playback cancelled")
            } finally {
                _isPlaying.value = false
                _currentStep.value = null
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
        _currentStep.value = null
    }

    private suspend fun executeStep(step: MacroStep): Boolean {
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "Accessibility Service is not running")
            return false
        }

        return when (step.actionType) {
            ActionType.TAP -> {
                if (step.startX == null || step.startY == null) return false
                suspendCancellableCoroutine { continuation ->
                    service.performClick(step.startX, step.startY) {
                        continuation.resume(true)
                    }
                }
            }
            ActionType.HOLD -> {
                if (step.startX == null || step.startY == null || step.duration == null) return false
                suspendCancellableCoroutine { continuation ->
                    service.performHold(step.startX, step.startY, step.duration) {
                        continuation.resume(true)
                    }
                }
            }
            ActionType.DRAG -> {
                if (step.startX == null || step.startY == null || step.endX == null || step.endY == null || step.duration == null) return false
                suspendCancellableCoroutine { continuation ->
                    service.performDrag(step.startX, step.startY, step.endX, step.endY, step.duration) {
                        continuation.resume(true)
                    }
                }
            }
            ActionType.SCROLL -> {
                if (step.startX == null || step.startY == null || step.endX == null || step.endY == null || step.duration == null) return false
                suspendCancellableCoroutine { continuation ->
                    service.performDrag(step.startX, step.startY, step.endX, step.endY, step.duration) {
                        continuation.resume(true)
                    }
                }
            }
            ActionType.DELAY -> {
                if (step.delayAfter > 0) {
                    delay(step.delayAfter)
                }
                true
            }
            ActionType.IMAGE_DETECTION -> {
                executeImageDetectionStep(step)
            }
        }
    }

    private suspend fun executeImageDetectionStep(step: MacroStep): Boolean {
        val templatePath = step.templateImagePath ?: return false
        val threshold = step.threshold ?: 0.85f
        val timeout = step.timeout ?: 5000L
        val detectionType = step.detectionType ?: DetectionType.WAIT_UNTIL_APPEAR
        val timeoutAction = step.timeoutAction ?: TimeoutAction.STOP

        val startTime = System.currentTimeMillis()
        var detected = false
        var matchX = 0f
        var matchY = 0f

        while (System.currentTimeMillis() - startTime < timeout) {
            val screenshot = captureScreenshot()
            if (screenshot != null) {
                val matchResult = openCVTemplateMatcher.match(
                    screenshot = screenshot,
                    templateImagePath = templatePath,
                    roiX = step.roiX,
                    roiY = step.roiY,
                    roiWidth = step.roiWidth,
                    roiHeight = step.roiHeight,
                    threshold = threshold
                )

                if (detectionType == DetectionType.WAIT_UNTIL_APPEAR || detectionType == DetectionType.CLICK_ON_APPEAR) {
                    if (matchResult.isMatch) {
                        detected = true
                        matchX = matchResult.x
                        matchY = matchResult.y
                        break
                    }
                } else if (detectionType == DetectionType.WAIT_UNTIL_DISAPPEAR) {
                    if (!matchResult.isMatch) {
                        detected = true
                        break
                    }
                }
            }
            delay(300L)
        }

        if (detected) {
            if (detectionType == DetectionType.CLICK_ON_APPEAR) {
                var clickX = matchX
                var clickY = matchY

                step.clickOffset?.let { offsetStr ->
                    val parts = offsetStr.split(",")
                    if (parts.size == 2) {
                        val dx = parts[0].toFloatOrNull() ?: 0f
                        val dy = parts[1].toFloatOrNull() ?: 0f
                        clickX += dx
                        clickY += dy
                    }
                }

                val service = AutoClickAccessibilityService.instance ?: return false
                suspendCancellableCoroutine { continuation ->
                    service.performClick(clickX, clickY) {
                        continuation.resume(true)
                    }
                }
            }
            return true
        } else {
            Log.w(TAG, "Image detection timed out for step ${step.sequenceOrder}")
            return when (timeoutAction) {
                TimeoutAction.STOP -> false
                TimeoutAction.SKIP -> true
            }
        }
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        service.captureScreen { bitmap ->
            continuation.resume(bitmap)
        }
    }
}
