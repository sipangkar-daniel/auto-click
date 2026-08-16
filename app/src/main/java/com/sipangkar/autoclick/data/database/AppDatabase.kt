package com.sipangkar.autoclick.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sipangkar.autoclick.data.database.dao.MacroDao
import com.sipangkar.autoclick.data.database.entity.MacroEntity
import com.sipangkar.autoclick.data.database.entity.MacroStepEntity

@Database(
    entities = [MacroEntity::class, MacroStepEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
}
