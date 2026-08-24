package com.zaid.densityreset.booster

import android.content.Context
import android.os.Build
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
    private val overlayController = GameStatsOverlayController(appContext)
    private val overlayPreferencesStore = GameOverlayPreferencesStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeAdapter: RomGameAdapter? = null
    private var activeProfile: DeviceProfile? = null
    private var thermalDowngradeRunning = false

    init {
        scope.launch {
            performanceMonitor.state.collect { monitor ->
                GameBoosterRuntime.mutableState.update { state ->
                    if (state.active) state.copy(monitor = monitor) else state
                }
                applyThermalFallbackIfNeeded(monitor.thermal?.level)
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
        val capabilities = adapter.detectCapabilities().copy(
            fixedPerformanceModeAvailable = detectFixedPerformanceModeCommand(),
            currentGameMode = diagnostic.currentMode,
            availableGameModes = diagnostic.availableModes
        )
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
            gameModeChanged = false,
            fixedPerformanceModeChanged = false
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
            BoosterMode.GAME,
            BoosterMode.BALANCED -> config.gameModeEnabled
            BoosterMode.BATTERY,
            BoosterMode.ULTRA_BATTERY -> config.batteryModeEnabled
            BoosterMode.MAX_PERFORMANCE,
            BoosterMode.ULTRA_MAX_PERFORMANCE -> config.maxPerformanceEnabled
        }

        var modeApplied = false
        var modeFailure: String? = null
        if (modeEnabled) {
            if (mode == BoosterMode.ULTRA_MAX_PERFORMANCE) {
                var performanceGameModeApplied = false
                if (capabilities.performanceModeAvailable) {
                    adapter.applyPerformanceMode(packageName).fold(
                        onSuccess = {
                            performanceGameModeApplied = true
                            snapshot = snapshot.copy(gameModeChanged = true)
                            snapshotStore.save(snapshot)
                            actions += BoosterAction(
                                name = "Game Mode",
                                detail = "Performance · parte del modo benchmark",
                                applied = true
                            )
                        },
                        onFailure = { error ->
                            actions += BoosterAction(
                                name = "Game Mode",
                                detail = error.message ?: "Performance no disponible.",
                                applied = false
                            )
                        }
                    )
                } else {
                    actions += BoosterAction(
                        name = "Game Mode",
                        detail = "Performance no está disponible para este juego.",
                        applied = false
                    )
                }

                val fixedResult = if (capabilities.fixedPerformanceModeAvailable) {
                    setFixedPerformanceMode(enabled = true)
                } else {
                    Result.failure(
                        IllegalStateException("Fixed Performance Mode no está disponible en este dispositivo.")
                    )
                }

                fixedResult.fold(
                    onSuccess = {
                        modeApplied = true
                        snapshot = snapshot.copy(fixedPerformanceModeChanged = true)
                        snapshotStore.save(snapshot)
                        actions += BoosterAction(
                            name = "Modo benchmark",
                            detail = "Fixed Performance Mode activo",
                            applied = true
                        )
                    },
                    onFailure = { error ->
                        modeFailure = error.message ?: "No se pudo activar el modo benchmark."
                        actions += BoosterAction(
                            name = "Modo benchmark",
                            detail = modeFailure.orEmpty(),
                            applied = false
                        )
                    }
                )

                if (!modeApplied && performanceGameModeApplied) {
                    val rollback = adapter.restore(snapshot)
                    if (rollback.isSuccess) {
                        snapshot = snapshot.copy(gameModeChanged = false)
                        snapshotStore.save(snapshot)
                        actions += BoosterAction(
                            name = "Restauración preventiva",
                            detail = "Game Mode volvió al valor anterior porque el modo benchmark no pudo activarse.",
                            applied = true
                        )
                    } else {
                        actions += BoosterAction(
                            name = "Restauración preventiva",
                            detail = rollback.exceptionOrNull()?.message
                                ?: "Game Mode requiere restauración al terminar la sesión.",
                            applied = false
                        )
                    }
                }
            } else {
                val result = when (mode) {
                    BoosterMode.GAME,
                    BoosterMode.BALANCED -> adapter.applyGameMode(packageName)
                    BoosterMode.BATTERY,
                    BoosterMode.ULTRA_BATTERY -> adapter.applyBatteryMode(packageName)
                    BoosterMode.MAX_PERFORMANCE -> adapter.applyPerformanceMode(packageName)
                    BoosterMode.ULTRA_MAX_PERFORMANCE -> error("Handled above")
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
            }
        } else {
            modeFailure = "Este modo está desactivado por configuración remota."
            actions += BoosterAction("Game Mode", modeFailure.orEmpty(), false)
        }

        val requestedFlags = monitorFlags(config, mode)
        val overlayPreference = overlayPreferencesStore.read(packageName)
        var hasMonitorWork = false
        if (requestedFlags.anyEnabled()) {
            performanceMonitor.start(packageName, requestedFlags, capabilities)
            val monitorActions = monitorActions(requestedFlags, capabilities)
            actions += monitorActions
            hasMonitorWork = monitorActions.any { it.applied }

            if (hasMonitorWork) {
                when {
                    !overlayPreference.enabled -> actions += BoosterAction(
                        name = "Overlay HUD",
                        detail = "Desactivado por el usuario. Los monitores siguen activos.",
                        applied = false
                    )
                    !overlayController.canDraw() -> actions += BoosterAction(
                        name = "Overlay HUD",
                        detail = "Sin permiso para mostrarse sobre otras apps. Los monitores siguen activos.",
                        applied = false
                    )
                    else -> {
                        val overlayStarted = overlayController.start(overlayPreference.normalizedOpacityPercent)
                        actions += BoosterAction(
                            name = "Overlay HUD",
                            detail = if (overlayStarted) {
                                "Activo · opacidad ${overlayPreference.normalizedOpacityPercent}%"
                            } else {
                                "Android rechazó la ventana flotante. Los monitores siguen activos."
                            },
                            applied = overlayStarted
                        )
                    }
                }
            }
        }

        val hasTemporaryWork = snapshot.gameModeChanged || snapshot.fixedPerformanceModeChanged
        val active = modeApplied || hasTemporaryWork || hasMonitorWork
        if (!active) {
            performanceMonitor.stop()
            overlayController.stop()
            snapshotStore.clear()
            activeAdapter = null
        }

        GameBoosterRuntime.mutableState.value = GameBoosterState(
            active = active,
            mode = mode,
            packageName = packageName,
            rom = profile.romFamily,
            deviceProfile = profile,
            capabilities = capabilities,
            actionsApplied = actions,
            monitor = if (active) performanceMonitor.state.value else GamePerformanceState(),
            message = modeFailure
        )

        return if (active) {
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
        val configuredAdapter = createAdapter(profile, snapshot.packageName, diagnostic, config)
        val restorationAdapter = if (snapshot.gameModeChanged) {
            createRestorationAdapter(snapshot, diagnostic)
        } else {
            configuredAdapter
        }
        val capabilities = configuredAdapter.detectCapabilities().copy(
            fixedPerformanceModeAvailable = detectFixedPerformanceModeCommand(),
            currentGameMode = diagnostic.currentMode,
            availableGameModes = diagnostic.availableModes
        )

        activeAdapter = restorationAdapter
        activeProfile = profile

        val requestedFlags = monitorFlags(config, snapshot.selectedMode)
        val overlayPreference = overlayPreferencesStore.read(snapshot.packageName)
        var hasMonitorWork = false
        if (requestedFlags.anyEnabled()) {
            performanceMonitor.start(snapshot.packageName, requestedFlags, capabilities)
            hasMonitorWork = monitorActions(requestedFlags, capabilities).any { it.applied }
            if (hasMonitorWork && overlayPreference.enabled && overlayController.canDraw()) {
                overlayController.start(overlayPreference.normalizedOpacityPercent)
            }
        }

        val hasTemporaryWork = snapshot.gameModeChanged || snapshot.fixedPerformanceModeChanged
        if (!hasTemporaryWork && !hasMonitorWork) {
            snapshotStore.clear()
            clearRuntime()
            return false
        }

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
                if (snapshot.fixedPerformanceModeChanged) {
                    add(BoosterAction("Modo benchmark", "Fixed Performance pendiente de restauración", true))
                }
                if (hasMonitorWork) {
                    add(BoosterAction(
                        "Overlay HUD",
                        when {
                            !overlayPreference.enabled -> "Desactivado por el usuario; monitores recuperados"
                            !overlayController.canDraw() -> "Sin permiso de overlay; monitores recuperados"
                            else -> "Monitor flotante recuperado · opacidad ${overlayPreference.normalizedOpacityPercent}%"
                        },
                        overlayPreference.enabled && overlayController.canDraw()
                    ))
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
        val capabilities = adapter.detectCapabilities().copy(
            fixedPerformanceModeAvailable = detectFixedPerformanceModeCommand(),
            currentGameMode = diagnostic.currentMode,
            availableGameModes = diagnostic.availableModes
        )
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
        overlayController.stop()
        performanceMonitor.stop()
        var snapshot = snapshotStore.read()
        if (snapshot == null) {
            clearRuntime()
            return BoosterResult.Success("Game Booster restaurado.")
        }

        val failures = mutableListOf<String>()

        if (snapshot.fixedPerformanceModeChanged) {
            val fixedRestore = setFixedPerformanceMode(enabled = false)
            if (fixedRestore.isSuccess) {
                snapshot = snapshot.copy(fixedPerformanceModeChanged = false)
                snapshotStore.save(snapshot)
            } else {
                failures += fixedRestore.exceptionOrNull()?.message
                    ?: "No se pudo desactivar Fixed Performance Mode."
            }
        }

        if (snapshot.gameModeChanged) {
            val diagnostic = GameModeCapabilityProbe(commandExecutor).diagnose(snapshot.packageName)
            val adapter = activeAdapter ?: createRestorationAdapter(snapshot, diagnostic)
            val restored = adapter.restore(snapshot)
            if (restored.isSuccess) {
                snapshot = snapshot.copy(gameModeChanged = false)
                snapshotStore.save(snapshot)
            } else {
                failures += restored.exceptionOrNull()?.message
                    ?: "No se pudo restaurar Game Mode."
            }
        }

        if (failures.isNotEmpty()) {
            val message = failures.joinToString(" ")
            GameBoosterRuntime.mutableState.update { it.copy(message = message) }
            return BoosterResult.Failure(message)
        }

        snapshotStore.clear()
        clearRuntime()
        return BoosterResult.Success("Game Booster restaurado.")
    }

    override fun observeState(): Flow<GameBoosterState> = GameBoosterRuntime.state

    suspend fun hasSnapshot(): Boolean = snapshotStore.read() != null

    fun close() {
        overlayController.close()
        performanceMonitor.close()
        scope.cancel()
    }

    private fun clearRuntime() {
        overlayController.stop()
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

    private fun createRestorationAdapter(
        snapshot: BoosterSnapshot,
        diagnostic: GameModeDiagnostic
    ): RomGameAdapter = AospRomAdapter(
        packageName = snapshot.packageName,
        commandExecutor = commandExecutor,
        diagnostic = diagnostic,
        romFamily = snapshot.romFamily
    )

    private suspend fun detectFixedPerformanceModeCommand(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val result = commandExecutor.execute(
            arrayOf("/system/bin/cmd", "power", "help"),
            timeoutSeconds = 5L
        ).getOrNull() ?: return false
        if (!result.isSuccess) return false
        return containsFixedPerformanceModeCommand(
            listOf(result.stdout, result.stderr).joinToString("\n")
        )
    }

    private suspend fun setFixedPerformanceMode(enabled: Boolean): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(
                IllegalStateException("Fixed Performance Mode requiere Android 11 o posterior.")
            )
        }
        val result = commandExecutor.execute(
            arrayOf(
                "/system/bin/cmd",
                "power",
                "set-fixed-performance-mode-enabled",
                enabled.toString()
            ),
            timeoutSeconds = 6L
        ).getOrElse { return Result.failure(it) }

        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    result.stderr.ifBlank {
                        result.stdout.ifBlank {
                            if (enabled) {
                                "El Power HAL no aceptó Fixed Performance Mode."
                            } else {
                                "El Power HAL no confirmó la restauración de Fixed Performance Mode."
                            }
                        }
                    }
                )
            )
        }
    }

    private fun monitorFlags(
        config: RemoteAppConfig,
        mode: BoosterMode?
    ): MonitorFlags = MonitorFlags(
        ram = config.ramMonitorEnabled,
        battery = config.batteryMonitorEnabled,
        thermal = config.thermalMonitorEnabled,
        fps = config.fpsMonitorEnabled && mode != BoosterMode.ULTRA_BATTERY
    )

    private suspend fun applyThermalFallbackIfNeeded(level: ThermalLevel?) {
        if (level == null || thermalDowngradeRunning) return
        val state = GameBoosterRuntime.mutableState.value
        if (!state.active) return
        val requested = thermalFallbackMode(
            current = state.mode,
            thermalLevel = level,
            batteryModeAvailable = state.capabilities.batteryModeAvailable
        ) ?: return
        val adapter = activeAdapter ?: return
        val storedSnapshot = snapshotStore.read() ?: return

        thermalDowngradeRunning = true
        try {
            var snapshot = storedSnapshot
            if (snapshot.fixedPerformanceModeChanged) {
                val fixedRestore = setFixedPerformanceMode(enabled = false)
                if (fixedRestore.isSuccess) {
                    snapshot = snapshot.copy(fixedPerformanceModeChanged = false)
                    snapshotStore.save(snapshot)
                }
            }

            val applied = when (requested) {
                BoosterMode.ULTRA_BATTERY -> adapter.applyBatteryMode(snapshot.packageName)
                BoosterMode.BALANCED -> adapter.applyGameMode(snapshot.packageName)
                else -> Result.failure(
                    IllegalStateException("Transición térmica no compatible.")
                )
            }
            applied.onSuccess {
                snapshot = snapshot.copy(
                    selectedMode = requested,
                    gameModeChanged = true
                )
                snapshotStore.save(snapshot)
                if (requested == BoosterMode.ULTRA_BATTERY) {
                    performanceMonitor.start(
                        snapshot.packageName,
                        monitorFlags(RemoteConfigManager.currentConfig(), requested),
                        state.capabilities
                    )
                }
                val message = if (requested == BoosterMode.ULTRA_BATTERY) {
                    "Temperatura crítica: se activó Ultra ahorro de batería."
                } else {
                    "Temperatura alta: se bajó automáticamente a Equilibrado."
                }
                GameBoosterRuntime.mutableState.update { current ->
                    current.copy(
                        mode = requested,
                        capabilities = current.capabilities.copy(
                            currentGameMode = if (requested == BoosterMode.ULTRA_BATTERY) {
                                "battery"
                            } else {
                                "standard"
                            }
                        ),
                        actionsApplied = current.actionsApplied + BoosterAction(
                            name = "Protección térmica adaptativa",
                            detail = message,
                            applied = true
                        ),
                        message = message,
                        thermalAdaptationMessage = message
                    )
                }
            }
        } finally {
            thermalDowngradeRunning = false
        }
    }

    private fun monitorActions(
        flags: MonitorFlags,
        capabilities: BoosterCapabilities
    ): List<BoosterAction> = buildList {
        if (flags.ram) {
            add(BoosterAction("Monitor RAM", if (capabilities.memoryMonitoringAvailable) "Overlay activo" else "No disponible", capabilities.memoryMonitoringAvailable))
        }
        if (flags.battery) {
            add(BoosterAction("Monitor batería", "Overlay activo", true))
        }
        if (flags.thermal) {
            add(BoosterAction("Monitor térmico", if (capabilities.thermalMonitoringAvailable) "Overlay activo" else "No disponible", capabilities.thermalMonitoringAvailable))
        }
        if (flags.fps) {
            add(BoosterAction("Monitor FPS", if (capabilities.fpsMonitoringAvailable) "gfxinfo framestats · overlay" else "No disponible en este dispositivo", capabilities.fpsMonitoringAvailable))
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
            prefs[Keys.fixedPerformanceModeChanged] = snapshot.fixedPerformanceModeChanged
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
            gameModeChanged = prefs[Keys.gameModeChanged] ?: false,
            fixedPerformanceModeChanged = prefs[Keys.fixedPerformanceModeChanged] ?: false
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
        val fixedPerformanceModeChanged = booleanPreferencesKey("fixed_performance_mode_changed")
    }
}

private val Context.boosterSnapshotDataStore by preferencesDataStore(
    name = "game_booster_snapshot"
)

internal fun containsFixedPerformanceModeCommand(output: String): Boolean =
    output.contains("set-fixed-performance-mode-enabled", ignoreCase = true)

private fun MonitorFlags.anyEnabled(): Boolean = ram || battery || thermal || fps

private fun BoosterMode.commandModeLabel(): String = when (this) {
    BoosterMode.GAME -> "Standard"
    BoosterMode.BALANCED -> "Standard · equilibrado"
    BoosterMode.BATTERY -> "Battery"
    BoosterMode.ULTRA_BATTERY -> "Battery · muestreo ligero"
    BoosterMode.MAX_PERFORMANCE -> "Performance"
    BoosterMode.ULTRA_MAX_PERFORMANCE -> "Performance + Fixed Performance"
}
