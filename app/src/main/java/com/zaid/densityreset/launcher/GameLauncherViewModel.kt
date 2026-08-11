package com.zaid.densityreset.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.GameSessionControllerImpl
import com.zaid.densityreset.gameprofile.domain.GameSessionResult
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.startup.StartupCoordinator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GameLauncherGameUiState(
    val game: SupportedGame,
    val applicationName: String,
    val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val installed: Boolean,
    val enabled: Boolean,
    val selectedProfile: DensityPreset,
    val lastProfile: DensityPreset?,
    val defaultProfile: DensityPreset?,
    val canPlay: Boolean
)

data class GameLauncherUiState(
    val games: List<GameLauncherGameUiState> = emptyList(),
    val session: GameSessionState = GameSessionState(),
    val announcementEnabled: Boolean = false,
    val announcementTitle: String? = null,
    val announcementMessage: String? = null,
    val busy: Boolean = false
)

class GameLauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: GameLauncherRepository = GameLauncherRepositoryImpl(application)
    private val sessionRepository = GameSessionRepositoryImpl(application)
    private val gameController = ShizukuGameController(application)
    private val sessionController = GameSessionControllerImpl(
        context = application,
        repository = sessionRepository,
        gameController = gameController
    )

    private val installed = mutableMapOf<SupportedGame, InstalledGameInfo>()
    private var preferences: Map<SupportedGame, GameLauncherPreference> = emptyMap()
    private var config: RemoteAppConfig = RemoteConfigManager.currentConfig()
    private var session = GameSessionState()
    private val selected = mutableMapOf<SupportedGame, DensityPreset>()
    private var startRequested = false

    private val _uiState = MutableStateFlow(GameLauncherUiState())
    val uiState: StateFlow<GameLauncherUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val shizukuListener: (ShizukuManager.State) -> Unit = {
        rebuild()
    }

    init {
        ShizukuManager.addStateListener(shizukuListener)
        refreshGames()

        viewModelScope.launch {
            repository.observePreferences().collect { value ->
                preferences = value
                seedSelections()
                rebuild()
            }
        }
        viewModelScope.launch {
            RemoteConfigManager.config.collect { value ->
                config = value
                ensureEnabledSelections()
                rebuild()
            }
        }
        viewModelScope.launch {
            sessionRepository.state.collect { value ->
                session = value
                if (value.sessionActive) startRequested = false
                rebuild()
            }
        }
    }

    fun refreshGames() {
        SupportedGame.entries.forEach { game ->
            installed[game] = repository.installedGame(game)
        }
        rebuild()
    }

    fun selectProfile(game: SupportedGame, preset: DensityPreset) {
        if (!isPresetEnabled(preset)) {
            _events.tryEmit("${preset.displayName} está temporalmente no disponible.")
            return
        }
        if (session.sessionActive || startRequested) return
        selected[game] = preset
        rebuild()
    }

    fun toggleDefaultProfile(game: SupportedGame) {
        if (session.sessionActive || startRequested) return
        val preset = selectedProfile(game)
        if (!isPresetEnabled(preset)) return
        val current = preferences[game]?.defaultProfile
        viewModelScope.launch {
            repository.setDefaultProfile(game, if (current == preset) null else preset)
        }
    }

    fun play(game: SupportedGame) {
        if (!StartupCoordinator.isReady()) {
            _events.tryEmit("La aplicación todavía está validando el acceso.")
            return
        }
        val info = installed[game] ?: repository.installedGame(game)
        if (!info.installed) {
            _events.tryEmit("${game.displayName} no está instalado.")
            return
        }
        if (!isGameEnabled(game)) {
            _events.tryEmit("${game.displayName} está temporalmente no disponible.")
            return
        }
        if (session.sessionActive || startRequested) {
            _events.tryEmit("Ya existe una sesión de DPI activa.")
            return
        }
        val preset = selectedProfile(game)
        if (!isPresetEnabled(preset)) {
            _events.tryEmit("${preset.displayName} está temporalmente no disponible.")
            return
        }

        startRequested = true
        rebuild()
        viewModelScope.launch {
            repository.setLastProfile(game, preset)
            when (val result = sessionController.startSession(game, preset)) {
                is GameSessionResult.Success -> _events.emit(result.message)
                is GameSessionResult.Failure -> {
                    startRequested = false
                    rebuild()
                    _events.emit(result.message)
                }
            }
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            when (val result = sessionController.restoreNow()) {
                is GameSessionResult.Success -> _events.emit(result.message)
                is GameSessionResult.Failure -> _events.emit(result.message)
            }
        }
    }

    private fun seedSelections() {
        SupportedGame.entries.forEach { game ->
            if (selected[game] == null) {
                val saved = preferences[game]
                selected[game] = saved?.defaultProfile
                    ?: saved?.lastProfile
                    ?: firstEnabledPreset()
            }
        }
    }

    private fun ensureEnabledSelections() {
        SupportedGame.entries.forEach { game ->
            val current = selected[game]
            if (current == null || !isPresetEnabled(current)) {
                selected[game] = firstEnabledPreset()
            }
        }
    }

    private fun selectedProfile(game: SupportedGame): DensityPreset =
        selected[game]
            ?: preferences[game]?.defaultProfile?.takeIf(::isPresetEnabled)
            ?: preferences[game]?.lastProfile?.takeIf(::isPresetEnabled)
            ?: firstEnabledPreset()

    private fun firstEnabledPreset(): DensityPreset =
        DensityPreset.entries.firstOrNull(::isPresetEnabled) ?: DensityPreset.HIGH

    private fun rebuild() {
        val shizuku = ShizukuManager.currentState()
        val busy = session.sessionActive || startRequested
        val games = SupportedGame.entries.map { game ->
            val info = installed[game] ?: repository.installedGame(game).also {
                installed[game] = it
            }
            val preference = preferences[game] ?: GameLauncherPreference()
            val profile = selectedProfile(game)
            GameLauncherGameUiState(
                game = game,
                applicationName = info.applicationName,
                packageName = info.packageName,
                versionCode = info.versionCode,
                lastUpdateTime = info.lastUpdateTime,
                installed = info.installed,
                enabled = isGameEnabled(game),
                selectedProfile = profile,
                lastProfile = preference.lastProfile,
                defaultProfile = preference.defaultProfile,
                canPlay = info.installed &&
                    isGameEnabled(game) &&
                    isPresetEnabled(profile) &&
                    shizuku.running &&
                    shizuku.permissionGranted &&
                    !busy &&
                    StartupCoordinator.isReady()
            )
        }
        _uiState.value = GameLauncherUiState(
            games = games,
            session = session,
            announcementEnabled = config.announcementEnabled,
            announcementTitle = config.announcementTitle,
            announcementMessage = config.announcementMessage,
            busy = busy
        )
    }

    private fun isGameEnabled(game: SupportedGame): Boolean = when (game) {
        SupportedGame.FREE_FIRE -> config.freeFireEnabled
        SupportedGame.FREE_FIRE_MAX -> config.freeFireMaxEnabled
    }

    fun isPresetEnabled(preset: DensityPreset): Boolean = when (preset) {
        DensityPreset.ULTRA -> config.ultraEnabled
        DensityPreset.VERY_HIGH -> config.veryHighEnabled
        DensityPreset.HIGH -> config.highEnabled
        DensityPreset.MEDIUM_HIGH -> config.mediumHighEnabled
        DensityPreset.LOW -> config.lowEnabled
    }

    override fun onCleared() {
        ShizukuManager.removeStateListener(shizukuListener)
        super.onCleared()
    }
}
