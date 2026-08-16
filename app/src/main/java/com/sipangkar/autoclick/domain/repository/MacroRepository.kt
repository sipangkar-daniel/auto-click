package com.sipangkar.autoclick.domain.repository

import com.sipangkar.autoclick.domain.model.Macro
import kotlinx.coroutines.flow.Flow

interface MacroRepository {
    fun getMacros(): Flow<List<Macro>>
    suspend fun getMacroById(id: Int): Macro?
    suspend fun saveMacro(macro: Macro)
    suspend fun deleteMacro(macro: Macro)
}
