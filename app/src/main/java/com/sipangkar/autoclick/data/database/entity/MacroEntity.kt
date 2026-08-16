package com.sipangkar.autoclick.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sipangkar.autoclick.domain.model.Macro

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val loopCount: Int,
    val createdAt: Long
) {
    fun toDomain(): Macro {
        return Macro(
            id = id,
            name = name,
            loopCount = loopCount,
            createdAt = createdAt,
            steps = emptyList()
        )
    }

    companion object {
        fun fromDomain(macro: Macro): MacroEntity {
            return MacroEntity(
                id = macro.id,
                name = macro.name,
                loopCount = macro.loopCount,
                createdAt = macro.createdAt
            )
        }
    }
}
