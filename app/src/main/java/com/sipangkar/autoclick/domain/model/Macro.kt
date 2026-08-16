package com.sipangkar.autoclick.domain.model

data class Macro(
    val id: Int = 0,
    val name: String,
    val loopCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: List<MacroStep> = emptyList()
)
