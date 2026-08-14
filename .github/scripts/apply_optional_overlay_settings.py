from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"No se encontró bloque esperado en {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


Path("app/src/main/java/com/zaid/densityreset/booster/GameOverlayPreferences.kt").write_text(
    '''package com.zaid.densityreset.booster

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val MIN_OVERLAY_OPACITY_PERCENT = 20
const val MAX_OVERLAY_OPACITY_PERCENT = 100
const val DEFAULT_OVERLAY_OPACITY_PERCENT = 85

fun normalizeOverlayOpacity(value: Int): Int =
    value.coerceIn(MIN_OVERLAY_OPACITY_PERCENT, MAX_OVERLAY_OPACITY_PERCENT)

data class GameOverlayPreference(
    val enabled: Boolean = true,
    val opacityPercent: Int = DEFAULT_OVERLAY_OPACITY_PERCENT
) {
    val normalizedOpacityPercent: Int
        get() = normalizeOverlayOpacity(opacityPercent)
}

class GameOverlayPreferencesStore(context: Context) {
    private val appContext = context.applicationContext

    fun observe(packageName: String): Flow<GameOverlayPreference> =
        appContext.gameOverlayDataStore.data.map { preferences ->
            GameOverlayPreference(
                enabled = preferences[enabledKey(packageName)] ?: true,
                opacityPercent = normalizeOverlayOpacity(
                    preferences[opacityKey(packageName)] ?: DEFAULT_OVERLAY_OPACITY_PERCENT
                )
            )
        }

    suspend fun read(packageName: String): GameOverlayPreference =
        observe(packageName).first()

    suspend fun setEnabled(packageName: String, enabled: Boolean) {
        appContext.gameOverlayDataStore.edit { preferences ->
            preferences[enabledKey(packageName)] = enabled
        }
    }

    suspend fun setOpacity(packageName: String, opacityPercent: Int) {
        appContext.gameOverlayDataStore.edit { preferences ->
            preferences[opacityKey(packageName)] = normalizeOverlayOpacity(opacityPercent)
        }
    }

    private fun enabledKey(packageName: String) =
        booleanPreferencesKey("${packageName.toPreferencePrefix()}_overlay_enabled")

    private fun opacityKey(packageName: String) =
        intPreferencesKey("${packageName.toPreferencePrefix()}_overlay_opacity")
}

private val Context.gameOverlayDataStore by preferencesDataStore(name = "game_overlay_preferences")

private fun String.toPreferencePrefix(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
'''
)

replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameStatsOverlayController.kt",
    '''    fun start(): Boolean {
        if (!canDraw()) return false
        if (root == null && !attachWindow()) return false

        collectorJob?.cancel()''',
    '''    fun start(opacityPercent: Int = DEFAULT_OVERLAY_OPACITY_PERCENT): Boolean {
        if (!canDraw()) return false
        val normalizedOpacity = normalizeOverlayOpacity(opacityPercent)
        if (root == null && !attachWindow(normalizedOpacity)) return false
        root?.alpha = normalizedOpacity / 100f

        collectorJob?.cancel()'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameStatsOverlayController.kt",
    '''    private fun attachWindow(): Boolean = runCatching {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(218, 15, 24, 37))
                setStroke(dp(1), Color.argb(150, 157, 234, 244))
            }
        }''',
    '''    private fun attachWindow(opacityPercent: Int): Boolean = runCatching {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            alpha = normalizeOverlayOpacity(opacityPercent) / 100f
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(15, 24, 37))
                setStroke(dp(1), Color.argb(180, 157, 234, 244))
            }
        }'''
)

replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameBoosterManager.kt",
    '''    private val performanceMonitor = GamePerformanceMonitor(appContext, commandExecutor)
    private val overlayController = GameStatsOverlayController(appContext)''',
    '''    private val performanceMonitor = GamePerformanceMonitor(appContext, commandExecutor)
    private val overlayController = GameStatsOverlayController(appContext)
    private val overlayPreferencesStore = GameOverlayPreferencesStore(appContext)'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameBoosterManager.kt",
    '''        val requestedFlags = monitorFlags(config)
        var hasMonitorWork = false
        if (requestedFlags.anyEnabled()) {
            if (overlayController.canDraw()) {
                performanceMonitor.start(packageName, requestedFlags, capabilities)
                val monitorActions = monitorActions(requestedFlags, capabilities)
                actions += monitorActions
                hasMonitorWork = monitorActions.any { it.applied }
                if (hasMonitorWork) {
                    val overlayStarted = overlayController.start()
                    actions += BoosterAction(
                        name = "Overlay HUD",
                        detail = if (overlayStarted) {
                            "FPS, RAM, batería y temperatura sobre el juego"
                        } else {
                            "Android rechazó la ventana flotante."
                        },
                        applied = overlayStarted
                    )
                    if (!overlayStarted) {
                        performanceMonitor.stop()
                        hasMonitorWork = false
                    }
                }
            } else {
                actions += BoosterAction(
                    name = "Overlay HUD",
                    detail = "Falta el permiso Mostrar sobre otras apps.",
                    applied = false
                )
            }
        }''',
    '''        val requestedFlags = monitorFlags(config)
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
        }'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameBoosterManager.kt",
    '''        val requestedFlags = monitorFlags(config)
        var hasMonitorWork = false
        if (requestedFlags.anyEnabled() && overlayController.canDraw()) {
            performanceMonitor.start(snapshot.packageName, requestedFlags, capabilities)
            hasMonitorWork = monitorActions(requestedFlags, capabilities).any { it.applied }
            if (hasMonitorWork && !overlayController.start()) {
                performanceMonitor.stop()
                hasMonitorWork = false
            }
        }''',
    '''        val requestedFlags = monitorFlags(config)
        val overlayPreference = overlayPreferencesStore.read(snapshot.packageName)
        var hasMonitorWork = false
        if (requestedFlags.anyEnabled()) {
            performanceMonitor.start(snapshot.packageName, requestedFlags, capabilities)
            hasMonitorWork = monitorActions(requestedFlags, capabilities).any { it.applied }
            if (hasMonitorWork && overlayPreference.enabled && overlayController.canDraw()) {
                overlayController.start(overlayPreference.normalizedOpacityPercent)
            }
        }'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/booster/GameBoosterManager.kt",
    '''                if (hasMonitorWork) {
                    add(BoosterAction("Overlay HUD", "Monitor flotante recuperado", true))
                }''',
    '''                if (hasMonitorWork) {
                    add(BoosterAction(
                        "Overlay HUD",
                        when {
                            !overlayPreference.enabled -> "Desactivado por el usuario; monitores recuperados"
                            !overlayController.canDraw() -> "Sin permiso de overlay; monitores recuperados"
                            else -> "Monitor flotante recuperado · opacidad ${overlayPreference.normalizedOpacityPercent}%"
                        },
                        overlayPreference.enabled && overlayController.canDraw()
                    ))
                }'''
)

replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''import com.zaid.densityreset.booster.GameBoosterManager
import com.zaid.densityreset.booster.GameBoosterState''',
    '''import com.zaid.densityreset.booster.GameBoosterManager
import com.zaid.densityreset.booster.GameBoosterState
import com.zaid.densityreset.booster.GameOverlayPreference
import com.zaid.densityreset.booster.GameOverlayPreferencesStore'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''    val defaultProfile: DensityPreset?,
    val boosterMode: BoosterMode,
    val canPlay: Boolean''',
    '''    val defaultProfile: DensityPreset?,
    val boosterMode: BoosterMode,
    val overlayEnabled: Boolean,
    val overlayOpacityPercent: Int,
    val canPlay: Boolean'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''    private val diagnosticBoosterManager = GameBoosterManager(application)

    private val installed''',
    '''    private val diagnosticBoosterManager = GameBoosterManager(application)
    private val overlayPreferencesStore = GameOverlayPreferencesStore(application)

    private val installed'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''    private var preferences: Map<SupportedGame, GameLauncherPreference> = emptyMap()
    private var config:''',
    '''    private var preferences: Map<SupportedGame, GameLauncherPreference> = emptyMap()
    private val overlayPreferences = mutableMapOf<SupportedGame, GameOverlayPreference>()
    private var config:'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''        ShizukuManager.addStateListener(shizukuListener)
        refreshGames()

        viewModelScope.launch {''',
    '''        ShizukuManager.addStateListener(shizukuListener)
        refreshGames()

        SupportedGame.entries.forEach { game ->
            viewModelScope.launch {
                overlayPreferencesStore.observe(game.packageName).collect { preference ->
                    overlayPreferences[game] = preference
                    rebuild()
                }
            }
        }

        viewModelScope.launch {'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''    fun toggleDefaultProfile(game: SupportedGame) {''',
    '''    fun setOverlayEnabled(game: SupportedGame, enabled: Boolean) {
        if (session.sessionActive || startRequested) return
        viewModelScope.launch {
            overlayPreferencesStore.setEnabled(game.packageName, enabled)
        }
    }

    fun setOverlayOpacity(game: SupportedGame, opacityPercent: Int) {
        if (session.sessionActive || startRequested) return
        viewModelScope.launch {
            overlayPreferencesStore.setOpacity(game.packageName, opacityPercent)
        }
    }

    fun toggleDefaultProfile(game: SupportedGame) {'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''    fun requiresPerformanceOverlay(): Boolean =
        config.gameBoosterEnabled && (
            config.ramMonitorEnabled ||
                config.batteryMonitorEnabled ||
                config.thermalMonitorEnabled ||
                config.fpsMonitorEnabled
            )''',
    '''    fun requiresPerformanceOverlay(game: SupportedGame): Boolean {
        val preference = overlayPreferences[game] ?: GameOverlayPreference()
        return preference.enabled &&
            config.gameBoosterEnabled && (
                config.ramMonitorEnabled ||
                    config.batteryMonitorEnabled ||
                    config.thermalMonitorEnabled ||
                    config.fpsMonitorEnabled
                )
    }'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''            val preference = preferences[game] ?: GameLauncherPreference()
            val profile = selectedProfile(game)
            GameLauncherGameUiState(''',
    '''            val preference = preferences[game] ?: GameLauncherPreference()
            val overlayPreference = overlayPreferences[game] ?: GameOverlayPreference()
            val profile = selectedProfile(game)
            GameLauncherGameUiState('''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherViewModel.kt",
    '''                defaultProfile = preference.defaultProfile,
                boosterMode = preference.boosterMode,
                canPlay =''',
    '''                defaultProfile = preference.defaultProfile,
                boosterMode = preference.boosterMode,
                overlayEnabled = overlayPreference.enabled,
                overlayOpacityPercent = overlayPreference.normalizedOpacityPercent,
                canPlay ='''
)

replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherActivity.kt",
    '''        if (game != null && Settings.canDrawOverlays(this)) {
            requestNotificationThenPlay(game)
        } else if (game != null) {
            Toast.makeText(
                this,
                "Activa ‘Mostrar sobre otras apps’ para ver FPS, RAM, batería y temperatura dentro del juego.",
                Toast.LENGTH_LONG
            ).show()
        }''',
    '''        if (game != null) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "El overlay no se mostrará porque no se concedió ‘Mostrar sobre otras apps’. El Game Booster continuará normalmente.",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestNotificationThenPlay(game)
        }'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherActivity.kt",
    '''                onSelectBoosterMode = viewModel::selectBoosterMode,
                onToggleDefault = viewModel::toggleDefaultProfile,''',
    '''                onSelectBoosterMode = viewModel::selectBoosterMode,
                onSetOverlayEnabled = viewModel::setOverlayEnabled,
                onSetOverlayOpacity = viewModel::setOverlayOpacity,
                onToggleDefault = viewModel::toggleDefaultProfile,'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherActivity.kt",
    '''        if (viewModel.requiresPerformanceOverlay() && !Settings.canDrawOverlays(this)) {''',
    '''        if (viewModel.requiresPerformanceOverlay(game) && !Settings.canDrawOverlays(this)) {'''
)

replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text''',
    '''import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''    onSelectBoosterMode: (SupportedGame, BoosterMode) -> Unit,
    onToggleDefault: (SupportedGame) -> Unit,''',
    '''    onSelectBoosterMode: (SupportedGame, BoosterMode) -> Unit,
    onSetOverlayEnabled: (SupportedGame, Boolean) -> Unit,
    onSetOverlayOpacity: (SupportedGame, Int) -> Unit,
    onToggleDefault: (SupportedGame) -> Unit,'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''                    onSelectBoosterMode = { onSelectBoosterMode(game.game, it) },
                    onToggleDefault = { onToggleDefault(game.game) },''',
    '''                    onSelectBoosterMode = { onSelectBoosterMode(game.game, it) },
                    onSetOverlayEnabled = { onSetOverlayEnabled(game.game, it) },
                    onSetOverlayOpacity = { onSetOverlayOpacity(game.game, it) },
                    onToggleDefault = { onToggleDefault(game.game) },'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''    onSelectProfile: (DensityPreset) -> Unit,
    onSelectBoosterMode: (BoosterMode) -> Unit,
    onToggleDefault: () -> Unit,''',
    '''    onSelectProfile: (DensityPreset) -> Unit,
    onSelectBoosterMode: (BoosterMode) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onSetOverlayOpacity: (Int) -> Unit,
    onToggleDefault: () -> Unit,'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''    var expanded by remember(state.game) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current''',
    '''    var expanded by remember(state.game) { mutableStateOf(false) }
    var overlayOpacityDraft by remember(state.game, state.overlayOpacityPercent) {
        mutableStateOf(state.overlayOpacityPercent.toFloat())
    }
    val haptic = LocalHapticFeedback.current'''
)
replace_once(
    "app/src/main/java/com/zaid/densityreset/launcher/GameLauncherScreen.kt",
    '''                        BoosterMode.entries.forEach { mode ->
                            BoosterModeRow(
                                mode = mode,
                                selected = state.boosterMode == mode,
                                enabled = isBoosterModeEnabled(mode) && !busy,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectBoosterMode(mode)
                                }
                            )
                        }
                    }''',
    '''                        BoosterMode.entries.forEach { mode ->
                            BoosterModeRow(
                                mode = mode,
                                selected = state.boosterMode == mode,
                                enabled = isBoosterModeEnabled(mode) && !busy,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectBoosterMode(mode)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mostrar overlay",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (state.overlayEnabled) {
                                        "FPS, RAM, batería y temperatura sobre el juego"
                                    } else {
                                        "Oculto. Los monitores siguen funcionando."
                                    },
                                    color = Color(0xFFC6CFDD),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = state.overlayEnabled,
                                onCheckedChange = { enabled ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSetOverlayEnabled(enabled)
                                },
                                enabled = !busy
                            )
                        }

                        if (state.overlayEnabled) {
                            Text(
                                "Opacidad ${overlayOpacityDraft.roundToInt()}% · Transparencia ${100 - overlayOpacityDraft.roundToInt()}%",
                                color = Color(0xFF9DEAF4),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = overlayOpacityDraft,
                                onValueChange = { overlayOpacityDraft = it },
                                onValueChangeFinished = {
                                    onSetOverlayOpacity(overlayOpacityDraft.roundToInt())
                                },
                                valueRange = 20f..100f,
                                enabled = !busy
                            )
                            Text(
                                "20% es muy transparente; 100% es completamente visible.",
                                color = Color(0xFFC6CFDD),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }'''
)

replace_once(
    "app/src/test/java/com/zaid/densityreset/booster/GameBoosterCoreTest.kt",
    '''    @Test
    fun remoteConfigOnlyEnablesCompiledBoosterBehaviors() {''',
    '''    @Test
    fun overlayOpacityIsClampedToUsefulRange() {
        assertEquals(20, normalizeOverlayOpacity(0))
        assertEquals(20, normalizeOverlayOpacity(20))
        assertEquals(85, normalizeOverlayOpacity(85))
        assertEquals(100, normalizeOverlayOpacity(100))
        assertEquals(100, normalizeOverlayOpacity(140))
        assertEquals(85, GameOverlayPreference().normalizedOpacityPercent)
        assertTrue(GameOverlayPreference().enabled)
    }

    @Test
    fun remoteConfigOnlyEnablesCompiledBoosterBehaviors() {'''
)

Path(".github/workflows/apply-optional-overlay-settings.yml").unlink(missing_ok=True)
Path(".github/scripts/apply_optional_overlay_settings.py").unlink(missing_ok=True)
