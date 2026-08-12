package com.zaid.densityreset.booster

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor
import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

object GameBoosterRuntime {
    internal val mutableState = MutableStateFlow(GameBoosterState())
    val state: Flow<GameBoosterState> = mutableState
}

class GameBoosterManager(
    context: Context,
    private val commandExecutor: ShizukuCommandExecutor = ShizukuCommandExecutor()
) : GameBoosterController {
    private val appContext = context.applicationContext
    private val detector = DeviceProfileDetector(commandExecutor)
    private val snapshotStore = BoosterSnapshotStore(appContext)
    private val performanceMonitor = GamePerformanceMonitor(appContext, commandExecutor)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeAdapter: RomGameAdapter? = null
    private var activeProfile: DeviceProfile? = null

    init {
        scope.launch {
            performanceMonitor.state.collect { monitor ->
                GameBoosterRuntime.mutableState.update { state ->
                    if (state.active) state.copy(monitor = monitor) else state
                }
            }
        }
    }

    override suspend fun prepare(
        packageName: String,
        mode: BoosterMode
    ): BoosterResult {
        val config = RemoteConfigManager.currentConfig()
        val profile = detector.detect()
        val diagnostic = GameModeCapabilityProbe(commandExecutor).diagnose(packageName)
        val adapter = createAdapter(profile, packageName, diagnostic, config)
        val capabilities = adapter.detectCapabilities()
        var snapshot = BoosterSnapshot(
            packageName = packageName,
            romFamily = profile.romFamily,
            previousGameMode = diagnostic.currentMode,
            previousValues = diagnostic.currentMode
                ?.let { mapOf("game_mode" to it) }
                .orEmpty(),
            startedAt = System.currentTimeMillis(),
            selectedMode = mode,
            vendor = profile.vendor,
            gameModeChanged = false
        )

        activeAdapter = adapter
        activeProfile = profile
        snapshotStore.save(snapshot)

        val actions = mutableListOf(
            BoosterAction(
                name = "Perfil ROM",
                detail = profile.adapterDisplayName,
                applied = true
            )
        )

        val modeEnabled = config.gameBoosterEnabled && when (mode) {
            BoosterMode.GAME -> config.gameModeEnabled
            BoosterMode.BATTERY -> config.batteryModeEnabled
            BoosterMode.MAX_PERFORMANCE -> config.maxPerformanceEnabled
        }

        var modeApplied = false
        var modeFailure: String? = null
        if (modeEnabled) {
            val result = when (mode) {
                BoosterMode.GAME -> adapter.applyGameMode(packageName)
                BoosterMode.BATTERY -> adapter.applyBatteryMode(packageName)
                BoosterMode.MAX_PERFORMANCE -> adapter.applyPerformanceMode(packageName)
            }
            result.fold(
                onSuccess = {
                    modeApplied = true
                    snapshot = snapshot.copy(gameModeChanged = true)
                    snapshotStore.save(snapshot)
                    actions += BoosterAction(
                        name = "Game Mode",
                        detail = mode.commandModeLabel(),
                        applied = true
                    )
                },
                onFailure = { error ->
                    modeFailure = error.message ?: "Game Booster no disponible en este dispositivo."
                    actions += BoosterAction(
                        name = "Game Mode",
                        detail = modeFailure.orEmpty(),
                        applied = false
                    )
                }
            )
        } else {
            modeFailure = "Este modo está desactivado por configuración remota."
            actions += BoosterAction("Game Mode", modeFailure.orEmpty(), false)
        }

        val flags = monitorFlags(config)
        performanceMonitor.start(packageName, flags, capabilities)
        val monitorActions = monitorActions(flags, capabilities)
        actions += monitorActions

        val hasMonitorWork = monitorActions.any { it.applied }
        val active = modeApplied || hasMonitorWork
        GameBoosterRuntime.mutableState.value = GameBoosterState(
            active = active,
            mode = mode,
            packageName = packageName,
            rom = profile.romFamily,
            deviceProfile = profile,
            capabilities = capabilities,
            actionsApplied = actions,
            monitor = performanceMonitor.state.value,
            message = modeFailure
        )

        return if (modeApplied || hasMonitorWork) {
            BoosterResult.Success(
                if (modeApplied) "${mode.displayName} activado." else "Monitores del juego activos.",
                actions
            )
        } else {
            BoosterResult.Failure(
                modeFailure ?: "Game Booster no disponible en este dispositivo."
            )
        }
    }

    suspend fun recoverIfNeeded(): Boolean {
        val snapshot = snapshotStore.read() ?: return false
        val config = RemoteConfigManager.currentConfig()
        val profile = detector.detect()
        val diagnostic = GameModeCapabilityProbe(commandExecutor).diagnose(snapshot.packageName)
        val adapter = createAdapter(profile, snapshot.packageName, diagnostic, config)
        val capabilities = adapter.detectCapabilities()

        activeAdapter = adapter
        activeProfile = profile
        performanceMonitor.start(snapshot.packageName, monitorFlags(config), capabilities)
        GameBoosterRuntime.mutableState.value = GameBoosterState(
            active = true,
            mode = snapshot.selectedMode,
            packageName = snapshot.packageName,
            rom = profile.romFamily,
            deviceProfile = profile,
            capabilities = capabilities,
            actionsApplied = buildList {
                add(BoosterAction("Perfil ROM", profile.adapterDisplayName, true))
                if (snapshot.gameModeChanged && snapshot.previousGameMode != null) {
                    add(BoosterAction("Game Mode", "Cambio temporal recuperado", true))
                }
                add(BoosterAction("Sesión", "Recuperada sin prolongar cambios temporales", true))
            },
            monitor = performanceMonitor.state.value
        )
        return true
    }

    suspend fun diagnose(packageName: String): GameBoosterState {
        val config = RemoteConfigManager.currentConfig()
        val profile = detector.detect()
        val diagnostic = GameModeCapabilityProbe(commandExecutor).diagnose(packageName)
        val adapter = createAdapter(profile, packageName, diagnostic, config)
        val capabilities = adapter.detectCapabilities()
        val current = GameBoosterRuntime.mutableState.value
        val diagnosed = current.copy(
            rom = profile.romFamily,
            deviceProfile = profile,
            capabilities = capabilities,
            message = null
        )
        GameBoosterRuntime.mutableState.value = diagnosed
        return diagnosed
    }

    override suspend fun restore(): BoosterResult {
        performanceMonitor.stop()
        val snapshot = snapshotStore.read()
        if (snapshot == null) {
            clearRuntime()
            return BoosterResult.Success("Game Booster restaurado.")
        }

        val config = RemoteConfigManager.currentConfig()
        val profile = activeProfile ?: detector.detect()
        val diagnostic = GameModeCapabilityProbe(commandExecutor).diagnose(snapshot.packageName)
        val adapter = activeAdapter ?: createAdapter(
            profile,
            snapshot.packageName,
            diagnostic,
            config
        )

        if (snapshot.gameModeChanged) {
            val restored = adapter.restore(snapshot)
            if (restored.isFailure) {
                val message = restored.exceptionOrNull()?.message
                    ?: "No se pudo restaurar Game Mode."
                GameBoosterRuntime.mutableState.update { it.copy(message = message) }
                return BoosterResult.Failure(message)
            }
        }

        snapshotStore.clear()
        clearRuntime()
        return BoosterResult.Success("Game Booster restaurado.")
    }

    override fun observeState(): Flow<GameBoosterState> = GameBoosterRuntime.state

    suspend fun hasSnapshot(): Boolean = snapshotStore.read() != null

    fun close() {
        performanceMonitor.close()
        scope.cancel()
    }

    private fun clearRuntime() {
        activeAdapter = null
        activeProfile = null
        GameBoosterRuntime.mutableState.value = GameBoosterState()
    }

    private fun createAdapter(
        profile: DeviceProfile,
        packageName: String,
        diagnostic: GameModeDiagnostic,
        config: RemoteAppConfig
    ): RomGameAdapter = createRomGameAdapter(
        profile = profile,
        packageName = packageName,
        commandExecutor = commandExecutor,
        diagnostic = diagnostic,
        xiaomiEnabled = config.xiaomiAdapterEnabled,
        samsungEnabled = config.samsungAdapterEnabled,
        oplusEnabled = config.oplusAdapterEnabled,
        aospEnabled = config.aospAdapterEnabled
    )

    private fun monitorFlags(config: RemoteAppConfig): MonitorFlags = MonitorFlags(
        ram = config.ramMonitorEnabled,
        battery = config.batteryMonitorEnabled,
        thermal = config.thermalMonitorEnabled,
        fps = config.fpsMonitorEnabled
    )

    private fun monitorActions(
        flags: MonitorFlags,
        capabilities: BoosterCapabilities
    ): List<BoosterAction> = buildList {
        if (flags.ram) {
            add(BoosterAction("Monitor RAM", if (capabilities.memoryMonitoringAvailable) "Activo" else "No disponible", capabilities.memoryMonitoringAvailable))
        }
        if (flags.battery) {
            add(BoosterAction("Monitor batería", "Activo", true))
        }
        if (flags.thermal) {
            add(BoosterAction("Monitor térmico", if (capabilities.thermalMonitoringAvailable) "Activo" else "No disponible", capabilities.thermalMonitoringAvailable))
        }
        if (flags.fps) {
            add(BoosterAction("Monitor FPS", if (capabilities.fpsMonitoringAvailable) "gfxinfo framestats" else "No disponible en este dispositivo", capabilities.fpsMonitoringAvailable))
        }
    }
}

private class BoosterSnapshotStore(private val context: Context) {
    suspend fun save(snapshot: BoosterSnapshot) {
        context.boosterSnapshotDataStore.edit { prefs ->
            prefs[Keys.packageName] = snapshot.packageName
            prefs[Keys.romFamily] = snapshot.romFamily.name
            prefs[Keys.vendor] = snapshot.vendor.name
            prefs[Keys.startedAt] = snapshot.startedAt
            prefs[Keys.gameModeChanged] = snapshot.gameModeChanged
            snapshot.previousGameMode?.let { prefs[Keys.previousGameMode] = it }
                ?: prefs.remove(Keys.previousGameMode)
            snapshot.selectedMode?.let { prefs[Keys.selectedMode] = it.name }
                ?: prefs.remove(Keys.selectedMode)
            prefs[Keys.previousValues] = JSONObject(snapshot.previousValues).toString()
        }
    }

    suspend fun read(): BoosterSnapshot? {
        val prefs = context.boosterSnapshotDataStore.data.first()
        val packageName = prefs[Keys.packageName] ?: return null
        val previousValues = runCatching {
            val json = JSONObject(prefs[Keys.previousValues].orEmpty())
            buildMap {
                json.keys().forEach { key -> put(key, json.optString(key)) }
            }
        }.getOrDefault(emptyMap())
        return BoosterSnapshot(
            packageName = packageName,
            romFamily = prefs[Keys.romFamily]
                ?.let { runCatching { RomFamily.valueOf(it) }.getOrNull() }
                ?: RomFamily.UNKNOWN,
            previousGameMode = prefs[Keys.previousGameMode],
            previousValues = previousValues,
            startedAt = prefs[Keys.startedAt] ?: System.currentTimeMillis(),
            selectedMode = prefs[Keys.selectedMode]
                ?.let { runCatching { BoosterMode.valueOf(it) }.getOrNull() },
            vendor = prefs[Keys.vendor]
                ?.let { runCatching { DeviceVendor.valueOf(it) }.getOrNull() }
                ?: DeviceVendor.GENERIC,
            gameModeChanged = prefs[Keys.gameModeChanged] ?: false
        )
    }

    suspend fun clear() {
        context.boosterSnapshotDataStore.edit { it.clear() }
    }

    private object Keys {
        val packageName = stringPreferencesKey("package_name")
        val romFamily = stringPreferencesKey("rom_family")
        val vendor = stringPreferencesKey("vendor")
        val previousGameMode = stringPreferencesKey("previous_game_mode")
        val previousValues = stringPreferencesKey("previous_values")
        val selectedMode = stringPreferencesKey("selected_mode")
        val startedAt = longPreferencesKey("started_at")
        val gameModeChanged = booleanPreferencesKey("game_mode_changed")
    }
}

private val Context.boosterSnapshotDataStore by preferencesDataStore(
    name = "game_booster_snapshot"
)

private fun BoosterMode.commandModeLabel(): String = when (this) {
    BoosterMode.GAME -> "Standard"
    BoosterMode.BATTERY -> "Battery"
    BoosterMode.MAX_PERFORMANCE -> "Performance"
}
