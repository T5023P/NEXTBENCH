package com.nextbench.app.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class OnboardingUiState(
    val completed: Boolean? = null,
    val isCompleting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: OnboardingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.completionState
                .catch {
                    _state.update {
                        it.copy(
                            completed = false,
                            error = "Setup progress could not be loaded. You can continue normally.",
                        )
                    }
                }
                .collect { completed -> _state.update { it.copy(completed = completed) } }
        }
    }

    fun complete() {
        if (_state.value.isCompleting || _state.value.completed == true) return
        _state.update { it.copy(isCompleting = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.complete() }.fold(
                onSuccess = {
                    _state.update { it.copy(completed = true, isCompleting = false, error = null) }
                },
                onFailure = {
                    _state.update {
                        it.copy(
                            isCompleting = false,
                            error = "Setup could not be saved. Check your storage and try again.",
                        )
                    }
                },
            )
        }
    }
}
