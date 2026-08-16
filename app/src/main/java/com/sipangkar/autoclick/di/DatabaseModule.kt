package com.sipangkar.autoclick.di

import android.content.Context
import androidx.room.Room
import com.sipangkar.autoclick.data.database.AppDatabase
import com.sipangkar.autoclick.data.database.dao.MacroDao
import com.sipangkar.autoclick.data.repository.MacroRepositoryImpl
import com.sipangkar.autoclick.domain.repository.MacroRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "autoclick_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideMacroDao(database: AppDatabase): MacroDao {
        return database.macroDao()
    }

    @Provides
    @Singleton
    fun provideMacroRepository(macroDao: MacroDao): MacroRepository {
        return MacroRepositoryImpl(macroDao)
    }
}
