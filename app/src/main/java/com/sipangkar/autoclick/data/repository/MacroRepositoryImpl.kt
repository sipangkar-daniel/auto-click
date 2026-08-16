package com.sipangkar.autoclick.data.repository

import com.sipangkar.autoclick.data.database.dao.MacroDao
import com.sipangkar.autoclick.data.database.entity.MacroEntity
import com.sipangkar.autoclick.data.database.entity.MacroStepEntity
import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.repository.MacroRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MacroRepositoryImpl @Inject constructor(
    private val macroDao: MacroDao
) : MacroRepository {

    override fun getMacros(): Flow<List<Macro>> {
        return macroDao.getMacrosWithSteps().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getMacroById(id: Int): Macro? {
        return macroDao.getMacroById(id)?.toDomain()
    }

    override suspend fun saveMacro(macro: Macro) {
        val macroEntity = MacroEntity.fromDomain(macro)
        val stepEntities = macro.steps.map { MacroStepEntity.fromDomain(it, macro.id) }
        macroDao.saveMacroWithSteps(macroEntity, stepEntities)
    }

    override suspend fun deleteMacro(macro: Macro) {
        val macroEntity = MacroEntity.fromDomain(macro)
        macroDao.deleteMacro(macroEntity)
    }
}
