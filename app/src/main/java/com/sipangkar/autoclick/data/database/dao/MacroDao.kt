package com.sipangkar.autoclick.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sipangkar.autoclick.data.database.entity.MacroEntity
import com.sipangkar.autoclick.data.database.entity.MacroStepEntity
import com.sipangkar.autoclick.data.database.entity.MacroWithSteps
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    
    @Transaction
    @Query("SELECT * FROM macros ORDER BY createdAt DESC")
    fun getMacrosWithSteps(): Flow<List<MacroWithSteps>>

    @Transaction
    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun getMacroById(id: Int): MacroWithSteps?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: MacroEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacroSteps(steps: List<MacroStepEntity>)

    @Query("DELETE FROM macro_steps WHERE macroId = :macroId")
    suspend fun deleteStepsForMacro(macroId: Int)

    @Delete
    suspend fun deleteMacro(macro: MacroEntity)

    @Transaction
    suspend fun saveMacroWithSteps(macro: MacroEntity, steps: List<MacroStepEntity>) {
        // If macro is a new entry (id = 0), this will insert and return generated ID.
        // If it's updating, it will reuse the existing ID.
        val macroId = insertMacro(macro).toInt()
        deleteStepsForMacro(macroId)
        val stepsWithMacroId = steps.map { it.copy(macroId = macroId) }
        insertMacroSteps(stepsWithMacroId)
    }
}
