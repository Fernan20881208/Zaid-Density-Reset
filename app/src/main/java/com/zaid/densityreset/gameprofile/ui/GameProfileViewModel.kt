package com.zaid.densityreset.gameprofile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.GameProfileUiState
import com.zaid.densityreset.gameprofile.domain.GameSessionControllerImpl
import com.zaid.densityreset.gameprofile.domain.GameSessionResult
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

class GameProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameSessionRepositoryImpl(application)
    private val gameController = ShizukuGameController(application)
    private val sessionController = GameSessionControllerImpl(
        context = application,
        repository = repository,
        gameController = gameController
    )

    private val _uiState = MutableStateFlow(GameProfileUiState())
    val uiState: StateFlow<GameProfileUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var persistedState = GameSessionState()
    private var localSelectedGame: SupportedGame? = null
    private var localSelectedPreset: DensityPreset? = null
    private var localStartRequested = false
    private var recoveryRequested = false
    private var remoteConfig = RemoteConfigManager.currentConfig()

    private val shizukuListener: (ShizukuManager.State) -> Unit = { state ->
        rebuildState(
            shizukuReady = state.running && state.permissionGranted
        )
    }

    init {
        ShizukuManager.addStateListener(shizukuListener)
        refreshEnvironment()

        viewModelScope.launch {
            RemoteConfigManager.config.collect { config ->
                remoteConfig = config
                rebuildState()
            }
        }

        viewModelScope.launch {
            repository.state.collect { state ->
                persistedState = state
                if (state.sessionActive) {
                    localStartRequested = false
                }
                if (state.sessionActive && !recoveryRequested) {
                    recoveryRequested = true
                    sessionController.recoverPendingSession()
                }
                if (!state.sessionActive) recoveryRequested = false

                state.lastResultMessage?.let { message ->
                    localStartRequested = false
                    _events.emit(message)
                    repository.consumeLastResult()
                }
                rebuildState()
            }
        }

        viewModelScope.launch {
            while (true) {
                if (persistedState.sessionActive) rebuildState()
                delay(COUNTDOWN_TICK_MILLIS)
            }
        }
    }

    fun refreshEnvironment() {
        rebuildState(
            installedGames = gameController.installedGames(),
            shizukuReady = ShizukuManager.currentState().let {
                it.running && it.permissionGranted
            }
        )
    }

    fun selectGame(game: SupportedGame) {
        if (isOperationLocked()) return
        if (!isGameEnabled(game)) {
            _events.tryEmit("${game.displayName} está temporalmente no disponible.")
            return
        }
        val installed = _uiState.value.installedGames
        if (game !in installed) {
            _events.tryEmit("Este juego no está instalado.")
            return
        }
        localSelectedGame = game
        rebuildState()
    }

    fun selectPreset(preset: DensityPreset) {
        if (isOperationLocked()) return
        if (!isPresetEnabled(preset)) {
            _events.tryEmit("${preset.displayName} está temporalmente no disponible.")
            return
        }
        localSelectedPreset = preset
        rebuildState()
    }

    fun startSession() {
        val state = _uiState.value
        val game = state.selectedGame
        val preset = state.selectedPreset
        if (game == null) {
            _events.tryEmit("Selecciona un juego.")
            return
        }
        if (preset == null) {
            _events.tryEmit("Selecciona un perfil.")
            return
        }
        if (!isGameEnabled(game)) {
            _events.tryEmit("${game.displayName} está temporalmente no disponible.")
            return
        }
        if (!isPresetEnabled(preset)) {
            _events.tryEmit("${preset.displayName} está temporalmente no disponible.")
            return
        }
        if (!state.canStart) {
            val message = when {
                game !in state.installedGames -> "Este juego no está instalado."
                !state.shizukuReady -> "Shizuku no está ejecutándose o no tiene permiso."
                state.sessionActive || localStartRequested ->
                    "Ya existe una sesión de DPI activa."
                else -> "No es posible iniciar la sesión en este momento."
            }
            _events.tryEmit(message)
            return
        }

        localStartRequested = true
        rebuildState()
        viewModelScope.launch {
            when (val result = sessionController.startSession(game, preset)) {
                is GameSessionResult.Success -> scheduleStartRequestGuard()
                is GameSessionResult.Failure -> {
                    localStartRequested = false
                    rebuildState()
                    _events.emit(result.message)
                }
            }
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            when (val result = sessionController.restoreNow()) {
                is GameSessionResult.Success -> Unit
                is GameSessionResult.Failure -> _events.emit(result.message)
            }
        }
    }

    private fun scheduleStartRequestGuard() {
        viewModelScope.launch {
            delay(START_REQUEST_GUARD_MILLIS)
            if (localStartRequested && !persistedState.sessionActive) {
                localStartRequested = false
                rebuildState()
                _events.emit("La sesión no pudo iniciarse. Inténtalo nuevamente.")
            }
        }
    }

    private fun rebuildState(
        installedGames: Set<SupportedGame> = _uiState.value.installedGames,
        shizukuReady: Boolean = _uiState.value.shizukuReady
    ) {
        val session = persistedState
        val selectedGame = if (session.sessionActive) {
            session.selectedGame
        } else {
            localSelectedGame
        }
        val selectedPreset = if (session.sessionActive) {
            session.selectedPreset
        } else {
            localSelectedPreset
        }
        val effectiveStep = when {
            session.sessionActive -> session.currentStep
            localStartRequested && session.currentStep in PRE_SESSION_STEPS ->
                session.currentStep
            localStartRequested -> SessionStep.VALIDATING
            else -> SessionStep.IDLE
        }
        val operationLocked = localStartRequested || session.sessionActive
        val secondsRemaining = if (session.sessionActive) {
            session.restoreAt?.let { restoreAt ->
                ceil(
                    (restoreAt - System.currentTimeMillis())
                        .coerceAtLeast(0L) / 1_000.0
                ).toInt()
            }
        } else {
            null
        }

        _uiState.value = GameProfileUiState(
            selectedGame = selectedGame,
            selectedPreset = selectedPreset,
            installedGames = installedGames,
            shizukuReady = shizukuReady,
            sessionActive = session.sessionActive,
            currentStep = effectiveStep,
            secondsRemaining = secondsRemaining,
            activeDensity = session.targetDensity,
            previousDensity = session.snapshot?.effectiveDensity,
            errorMessage = session.errorMessage,
            canStart = selectedGame != null &&
                selectedPreset != null &&
                selectedGame in installedGames &&
                isGameEnabled(selectedGame) &&
                isPresetEnabled(selectedPreset) &&
                shizukuReady &&
                !operationLocked
        )
    }

    private fun isGameEnabled(game: SupportedGame): Boolean = when (game) {
        SupportedGame.FREE_FIRE -> remoteConfig.freeFireEnabled
        SupportedGame.FREE_FIRE_MAX -> remoteConfig.freeFireMaxEnabled
    }

    private fun isPresetEnabled(preset: DensityPreset): Boolean = when (preset) {
        DensityPreset.ULTRA -> remoteConfig.ultraEnabled
        DensityPreset.HIGH -> remoteConfig.highEnabled
        DensityPreset.LOW -> remoteConfig.lowEnabled
    }

    private fun isOperationLocked(): Boolean =
        localStartRequested || persistedState.sessionActive

    override fun onCleared() {
        ShizukuManager.removeStateListener(shizukuListener)
        super.onCleared()
    }

    private companion object {
        const val COUNTDOWN_TICK_MILLIS = 500L
        const val START_REQUEST_GUARD_MILLIS = 20_000L

        val PRE_SESSION_STEPS = setOf(
            SessionStep.VALIDATING,
            SessionStep.SAVING_DENSITY,
            SessionStep.CLOSING_GAME,
            SessionStep.APPLYING_DENSITY,
            SessionStep.VERIFYING_DENSITY,
            SessionStep.OPENING_GAME
        )
    }
}
