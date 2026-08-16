package com.sipangkar.autoclick.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sipangkar.autoclick.domain.model.Macro
import com.sipangkar.autoclick.domain.usecase.DeleteMacroUseCase
import com.sipangkar.autoclick.domain.usecase.GetMacrosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MacroViewModel @Inject constructor(
    private val getMacrosUseCase: GetMacrosUseCase,
    private val deleteMacroUseCase: DeleteMacroUseCase
) : ViewModel() {

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    init {
        loadMacros()
    }

    fun loadMacros() {
        viewModelScope.launch {
            try {
                getMacrosUseCase().collect { list ->
                    _macros.value = list
                }
            } catch (e: Exception) {
                // Ignore error in presentation layer
            }
        }
    }

    fun deleteMacro(macro: Macro) {
        viewModelScope.launch {
            try {
                deleteMacroUseCase(macro)
            } catch (e: Exception) {
                // Ignore error in presentation layer
            }
        }
    }
}
