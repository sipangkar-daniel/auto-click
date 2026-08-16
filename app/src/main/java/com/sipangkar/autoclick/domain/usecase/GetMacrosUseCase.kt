package com.sipangkar.autoclick.domain.usecase

import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.repository.MacroRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMacrosUseCase @Inject constructor(
    private val repository: MacroRepository
) {
    operator fun invoke(): Flow<List<Macro>> {
        return repository.getMacros()
    }
}
