package com.sipangkar.autoclick.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sipangkar.autoclick.domain.model.ActionType
import com.sipangkar.autoclick.domain.model.DetectionType
import com.sipangkar.autoclick.domain.model.MacroStep
import com.sipangkar.autoclick.domain.model.TimeoutAction

@Entity(
    tableName = "macro_steps",
    foreignKeys = [
        ForeignKey(
            entity = MacroEntity::class,
            parentColumns = ["id"],
            childColumns = ["macroId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["macroId"])]
)
data class MacroStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val macroId: Int,
    val sequenceOrder: Int,
    val actionType: String,
    
    // Coordinates
    val startX: Float?,
    val startY: Float?,
    val endX: Float?,
    val endY: Float?,
    
    // Timing / Durations
    val duration: Long?,
    val delayAfter: Long,
    
    // Image Detection
    val templateImagePath: String?,
    val roiX: Int?,
    val roiY: Int?,
    val roiWidth: Int?,
    val roiHeight: Int?,
    val threshold: Float?,
    val timeout: Long?,
    val detectionType: String?,
    val timeoutAction: String?,
    val clickOffset: String?
) {
    fun toDomain(): MacroStep {
        return MacroStep(
            id = id,
            macroId = macroId,
            sequenceOrder = sequenceOrder,
            actionType = ActionType.valueOf(actionType),
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            delayAfter = delayAfter,
            templateImagePath = templateImagePath,
            roiX = roiX,
            roiY = roiY,
            roiWidth = roiWidth,
            roiHeight = roiHeight,
            threshold = threshold,
            timeout = timeout,
            detectionType = detectionType?.let { DetectionType.valueOf(it) },
            timeoutAction = timeoutAction?.let { TimeoutAction.valueOf(it) } ?: TimeoutAction.STOP,
            clickOffset = clickOffset
        )
    }

    companion object {
        fun fromDomain(step: MacroStep, macroId: Int): MacroStepEntity {
            return MacroStepEntity(
                id = step.id,
                macroId = macroId,
                sequenceOrder = step.sequenceOrder,
                actionType = step.actionType.name,
                startX = step.startX,
                startY = step.startY,
                endX = step.endX,
                endY = step.endY,
                duration = step.duration,
                delayAfter = step.delayAfter,
                templateImagePath = step.templateImagePath,
                roiX = step.roiX,
                roiY = step.roiY,
                roiWidth = step.roiWidth,
                roiHeight = step.roiHeight,
                threshold = step.threshold,
                timeout = step.timeout,
                detectionType = step.detectionType?.name,
                timeoutAction = step.timeoutAction?.name,
                clickOffset = step.clickOffset
            )
        }
    }
}
