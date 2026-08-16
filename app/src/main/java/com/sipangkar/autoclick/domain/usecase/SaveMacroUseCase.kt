package com.sipangkar.autoclick.domain.usecase

import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.repository.MacroRepository
import javax.inject.Inject

class SaveMacroUseCase @Inject constructor(
    private val repository: MacroRepository
) {
    suspend operator fun invoke(macro: Macro) {
        // Validate name is not blank
        if (macro.name.isBlank()) {
            throw IllegalArgumentException("Macro name cannot be blank")
        }
        repository.saveMacro(macro)
    }
}
