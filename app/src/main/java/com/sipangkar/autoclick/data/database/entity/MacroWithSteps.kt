package com.sipangkar.autoclick.data.database.entity

import androidx.room.Embedded
import androidx.room.Relation
import com.sipangkar.autoclick.domain.model.Macro

data class MacroWithSteps(
    @Embedded val macro: MacroEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "macroId"
    )
    val steps: List<MacroStepEntity>
) {
    fun toDomain(): Macro {
        return macro.toDomain().copy(
            steps = steps.sortedBy { it.sequenceOrder }.map { it.toDomain() }
        )
    }
}
