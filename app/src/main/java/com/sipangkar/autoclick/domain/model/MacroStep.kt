package com.sipangkar.autoclick.domain.model

data class MacroStep(
    val id: Int = 0,
    val macroId: Int = 0,
    val sequenceOrder: Int,
    val actionType: ActionType,
    
    // Coordinates (for Tap, Hold, Drag, Scroll)
    val startX: Float? = null,
    val startY: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    
    // Timing / Durations
    val duration: Long? = null, // Hold or drag duration in ms
    val delayAfter: Long = 1000L, // Delay after execution in ms
    
    // Image Detection parameters
    val templateImagePath: String? = null,
    val roiX: Int? = null,
    val roiY: Int? = null,
    val roiWidth: Int? = null,
    val roiHeight: Int? = null,
    val threshold: Float? = 0.85f,
    val timeout: Long? = 5000L,
    val detectionType: DetectionType? = null,
    val timeoutAction: TimeoutAction? = TimeoutAction.STOP,
    val clickOffset: String? = null // Format "x,y"
)

enum class ActionType {
    TAP, HOLD, DRAG, SCROLL, IMAGE_DETECTION, DELAY
}

enum class DetectionType {
    WAIT_UNTIL_APPEAR, WAIT_UNTIL_DISAPPEAR, CLICK_ON_APPEAR
}

enum class TimeoutAction {
    STOP, SKIP
}
