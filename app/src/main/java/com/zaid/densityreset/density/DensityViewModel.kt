package com.zaid.densityreset.density

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DensityUiState(
    val isRefreshing: Boolean = true,
    val isApplying: Boolean = false,
    val initialDensity: Int? = null,
    val currentDensity: Int? = null,
    val hasOverride: Boolean = false,
    val activePreset: DensityPreset? = null,
    val statusLabel: String = "Comprobando DPI…",
    val operationMessage: String = "",
    val lastChangedAt: Long? = null
)

class DensityViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = ShizukuDensityController(application)
    private val repository = DensityPreferencesRepository(application)
    private val operationMutex = Mutex()

    private val _uiState = MutableStateFlow(DensityUiState())
    val uiState: StateFlow<DensityUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isApplying) return
        viewModelScope.launch {
            operationMutex.withLock {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
                val persisted = repository.read()
                controller.getSystemState()
                    .onSuccess { systemState ->
                        repository.saveOriginalDensityIfAbsent(systemState.initialDensity)
                        repository.saveObservedState(systemState)
                        _uiState.value = systemState.toUiState(
                            isRefreshing = false,
                            isApplying = false,
                            operationMessage = "",
                            lastChangedAt = persisted.lastChangedAt
                        )
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            operationMessage = userMessage(error)
                        )
                    }
            }
        }
    }

    fun applyPreset(preset: DensityPreset) {
        viewModelScope.launch {
            operationMutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isApplying = true,
                    operationMessage = ""
                )

                val before = controller.getSystemState().getOrElse { error ->
                    showPersistentError(error)
                    return@withLock
                }

                repository.saveOriginalDensityIfAbsent(before.initialDensity)

                controller.applyDensity(preset.density)
                    .onSuccess {
                        val verified = controller.getSystemState().getOrElse { error ->
                            showPersistentError(error)
                            return@withLock
                        }

                        if (verified.currentDensity != preset.density) {
                            _uiState.value = verified.toUiState(
                                isRefreshing = false,
                                isApplying = false,
                                operationMessage = "No se pudo verificar el DPI aplicado.",
                                lastChangedAt = _uiState.value.lastChangedAt
                            )
                            return@withLock
                        }

                        val changedAt = System.currentTimeMillis()
                        repository.saveAppliedPreset(preset, verified, changedAt)
                        _uiState.value = verified.toUiState(
                            isRefreshing = false,
                            isApplying = false,
                            operationMessage = "",
                            lastChangedAt = changedAt
                        )
                        _events.emit("DPI aplicado correctamente")
                    }
                    .onFailure { error ->
                        val observed = controller.getSystemState().getOrNull()
                        _uiState.value = if (observed != null) {
                            observed.toUiState(
                                isRefreshing = false,
                                isApplying = false,
                                operationMessage = userMessage(error),
                                lastChangedAt = _uiState.value.lastChangedAt
                            )
                        } else {
                            _uiState.value.copy(
                                isApplying = false,
                                operationMessage = userMessage(error)
                            )
                        }
                    }
            }
        }
    }

    fun recordExternalReset() {
        viewModelScope.launch {
            operationMutex.withLock {
                controller.getSystemState()
                    .onSuccess { verified ->
                        val changedAt = System.currentTimeMillis()
                        if (!verified.hasOverride) {
                            repository.saveReset(verified, changedAt)
                        } else {
                            repository.saveObservedState(verified, changedAt)
                        }
                        _uiState.value = verified.toUiState(
                            isRefreshing = false,
                            isApplying = false,
                            operationMessage = if (verified.hasOverride) {
                                "No se pudo verificar el DPI aplicado."
                            } else {
                                ""
                            },
                            lastChangedAt = changedAt
                        )
                        if (!verified.hasOverride) {
                            _events.emit("DPI restablecido correctamente.")
                        }
                    }
                    .onFailure { error -> showPersistentError(error) }
            }
        }
    }

    fun resetDensity() {
        viewModelScope.launch {
            operationMutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isApplying = true,
                    operationMessage = ""
                )

                controller.resetDensity()
                    .onSuccess {
                        val verified = controller.getSystemState().getOrElse { error ->
                            showPersistentError(error)
                            return@withLock
                        }

                        if (verified.hasOverride) {
                            _uiState.value = verified.toUiState(
                                isRefreshing = false,
                                isApplying = false,
                                operationMessage = "No se pudo verificar el DPI aplicado.",
                                lastChangedAt = _uiState.value.lastChangedAt
                            )
                            return@withLock
                        }

                        val changedAt = System.currentTimeMillis()
                        repository.saveReset(verified, changedAt)
                        _uiState.value = verified.toUiState(
                            isRefreshing = false,
                            isApplying = false,
                            operationMessage = "",
                            lastChangedAt = changedAt
                        )
                        _events.emit("DPI restablecido correctamente.")
                    }
                    .onFailure { error -> showPersistentError(error) }
            }
        }
    }

    private fun showPersistentError(error: Throwable) {
        _uiState.value = _uiState.value.copy(
            isRefreshing = false,
            isApplying = false,
            operationMessage = userMessage(error)
        )
    }

    private fun DensitySystemState.toUiState(
        isRefreshing: Boolean,
        isApplying: Boolean,
        operationMessage: String,
        lastChangedAt: Long?
    ): DensityUiState {
        val preset = if (hasOverride) {
            DensityPreset.fromDensity(currentDensity)
        } else {
            null
        }

        val label = when {
            !hasOverride -> "DPI original · $currentDensity DPI"
            preset != null -> "${preset.displayName} · ${preset.density} DPI"
            else -> "DPI personalizado · $currentDensity DPI"
        }

        return DensityUiState(
            isRefreshing = isRefreshing,
            isApplying = isApplying,
            initialDensity = initialDensity,
            currentDensity = currentDensity,
            hasOverride = hasOverride,
            activePreset = preset,
            statusLabel = label,
            operationMessage = operationMessage,
            lastChangedAt = lastChangedAt
        )
    }

    private fun userMessage(error: Throwable): String =
        (error as? DensityControlException)?.message
            ?: error.message
            ?: "No fue posible acceder a WindowManager."
}
