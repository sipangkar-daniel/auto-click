package com.sipangkar.autoclick.domain.usecase

import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.repository.MacroRepository
import javax.inject.Inject

class DeleteMacroUseCase @Inject constructor(
    private val repository: MacroRepository
) {
    suspend operator fun invoke(macro: Macro) {
        repository.deleteMacro(macro)
    }
}
