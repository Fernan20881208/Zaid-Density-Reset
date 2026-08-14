package com.zaid.densityreset.launcher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaid.densityreset.booster.BatteryInfo
import com.zaid.densityreset.booster.BoosterMode
import com.zaid.densityreset.booster.GameBoosterState
import com.zaid.densityreset.booster.RamInfo
import com.zaid.densityreset.booster.RamLevel
import com.zaid.densityreset.booster.ThermalInfo
import com.zaid.densityreset.booster.ThermalLevel
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.icons.AppIconRepositoryProvider
import com.zaid.densityreset.icons.AppIconResult
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GameLauncherScreen(
    state: GameLauncherUiState,
    isPresetEnabled: (DensityPreset) -> Boolean,
    isBoosterModeEnabled: (BoosterMode) -> Boolean,
    onSelectProfile: (SupportedGame, DensityPreset) -> Unit,
    onSelectBoosterMode: (SupportedGame, BoosterMode) -> Unit,
    onSetOverlayEnabled: (SupportedGame, Boolean) -> Unit,
    onSetOverlayOpacity: (SupportedGame, Int) -> Unit,
    onToggleDefault: (SupportedGame) -> Unit,
    onPlay: (SupportedGame) -> Unit,
    onRestore: () -> Unit,
    onRedetectDevice: () -> Unit,
    onOpenLegacyControls: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07101F), Color(0xFF172B47), Color(0xFF080D17))
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Density Reset", color = Color(0xFFE8EDF5))
                    Text(
                        "Game Launcher",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Elige el juego, sensibilidad y Game Booster. La app detecta automáticamente qué funciones admite tu dispositivo.",
                        color = Color(0xFFC6CFDD),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (
                state.announcementEnabled &&
                (!state.announcementTitle.isNullOrBlank() || !state.announcementMessage.isNullOrBlank())
            ) {
                item {
                    GlassPanel(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            state.announcementTitle ?: "Aviso",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        state.announcementMessage?.let {
                            Text(it, color = Color(0xFFE8EDF5))
                        }
                    }
                }
            }

            if (state.session.sessionActive) {
                item {
                    ActiveSessionCard(
                        state = state,
                        onRestore = onRestore,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (state.session.sessionActive && state.booster.active) {
                item {
                    PerformanceCard(
                        booster = state.booster,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            state.booster.deviceProfile?.let {
                item {
                    DeviceDiagnosticsCard(
                        booster = state.booster,
                        onRedetectDevice = onRedetectDevice,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            items(state.games, key = { it.packageName }) { game ->
                GameCard(
                    state = game,
                    busy = state.busy,
                    boosterEnabled = state.boosterEnabled,
                    isPresetEnabled = isPresetEnabled,
                    isBoosterModeEnabled = isBoosterModeEnabled,
                    onSelectProfile = { onSelectProfile(game.game, it) },
                    onSelectBoosterMode = { onSelectBoosterMode(game.game, it) },
                    onSetOverlayEnabled = { onSetOverlayEnabled(game.game, it) },
                    onSetOverlayOpacity = { onSetOverlayOpacity(game.game, it) },
                    onToggleDefault = { onToggleDefault(game.game) },
                    onPlay = { onPlay(game.game) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                Button(
                    onClick = onOpenLegacyControls,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x5C375374),
                        contentColor = Color.White
                    )
                ) {
                    Text("CONTROLES Y AJUSTES")
                }
            }
            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    state: GameLauncherUiState,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(modifier) {
        Text("Sesión activa", color = Color(0xFF98F0BC), fontWeight = FontWeight.Bold)
        val game = state.session.selectedGame
        val preset = state.session.selectedPreset
        val mode = state.booster.mode
        Text(
            listOfNotNull(
                game?.displayName,
                preset?.let { "${it.displayName} · ${state.session.targetDensity ?: it.density} DPI" },
                mode?.displayName
            ).joinToString(" · "),
            color = Color.White
        )
        state.session.restoreAt?.let {
            Text(
                "El DPI vuelve a la normalidad automáticamente a los 20 segundos. El Game Booster continúa mientras juegas.",
                color = Color(0xFFC6CFDD),
                style = MaterialTheme.typography.bodySmall
            )
        }
        PrimaryButton("RESTAURAR TODO AHORA", true, onRestore)
    }
}

@Composable
private fun PerformanceCard(
    booster: GameBoosterState,
    modifier: Modifier = Modifier
) {
    var selectedDetail by remember { mutableStateOf<String?>(null) }
    val monitor = booster.monitor
    val fps = monitor.fps?.fps
    val ram = monitor.ram
    val battery = monitor.battery
    val thermal = monitor.thermal

    GlassPanel(modifier) {
        Text("Estado del juego", color = Color.White, fontWeight = FontWeight.Bold)
        fps?.let {
            val label = when {
                it >= 55f -> "Fluido · Bien"
                it >= 35f -> "Variable · Atención"
                else -> "Bajo · Atención"
            }
            MetricRow("FPS", it.roundToInt().toString(), label) {
                selectedDetail = if (selectedDetail == "fps") null else "fps"
            }
        } ?: MetricRow("FPS", "No disponible", "Sin datos válidos") {
            selectedDetail = if (selectedDetail == "fps") null else "fps"
        }

        ram?.let {
            MetricRow(
                "RAM",
                "${formatGiB(it.availableBytes)} GB libres",
                ramStatus(it)
            ) { selectedDetail = if (selectedDetail == "ram") null else "ram" }
        }

        battery?.let {
            MetricRow(
                "Batería",
                "${it.percent}%",
                if (it.charging) "Cargando" else if (it.percent <= 15) "Atención" else "Normal"
            ) { selectedDetail = if (selectedDetail == "battery") null else "battery" }
        }

        thermal?.let {
            MetricRow(
                "Temperatura",
                it.temperatureCelsius?.let { value -> "${value.roundToInt()}°C" }
                    ?: it.level.displayName,
                thermalStatus(it)
            ) { selectedDetail = if (selectedDetail == "thermal") null else "thermal" }
        }

        selectedDetail?.let { metric ->
            MetricDetail(metric, ram, battery, thermal, fps)
        }

        if (thermal?.level == ThermalLevel.VERY_HOT) {
            Text(
                "El teléfono está muy caliente. El rendimiento puede bajar para proteger el dispositivo.",
                color = Color(0xFFFFD28E),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        } else if (
            booster.mode == BoosterMode.MAX_PERFORMANCE &&
            thermal?.level in setOf(ThermalLevel.WARM, ThermalLevel.HOT)
        ) {
            Text(
                "El dispositivo está caliente. Android puede reducir el rendimiento para controlar la temperatura.",
                color = Color(0xFFFFD28E),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    status: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFFC6CFDD))
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("● $status", color = statusColor(status), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MetricDetail(
    metric: String,
    ram: RamInfo?,
    battery: BatteryInfo?,
    thermal: ThermalInfo?,
    fps: Float?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x55243D59), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        when (metric) {
            "ram" -> ram?.let {
                Text("Memoria disponible", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "${formatGiB(it.availableBytes)} GB de ${formatGiB(it.totalBytes)} GB",
                    color = Color.White
                )
                Text(
                    when (it.level) {
                        RamLevel.EXCELLENT -> "El teléfono tiene suficiente memoria disponible para jugar."
                        RamLevel.NORMAL -> "La memoria disponible es normal para continuar jugando."
                        RamLevel.LOW -> "Queda poca memoria disponible. Android puede cerrar aplicaciones en segundo plano."
                    },
                    color = Color(0xFFC6CFDD)
                )
            }
            "battery" -> battery?.let {
                Text("Batería", color = Color.White, fontWeight = FontWeight.Bold)
                Text("${it.percent}% · ${if (it.charging) "Cargando" else "Descargando"}", color = Color.White)
                it.consumedSinceStart?.let { consumed ->
                    Text(
                        "Inicio: ${it.startPercent ?: it.percent}% · Actual: ${it.percent}% · Consumo: $consumed%",
                        color = Color(0xFFC6CFDD)
                    )
                }
            }
            "thermal" -> thermal?.let {
                Text("Temperatura", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    it.temperatureCelsius?.let { value -> "${value.roundToInt()} °C · ${it.level.displayName}" }
                        ?: "Estado térmico: ${it.level.displayName}",
                    color = Color.White
                )
                Text(
                    "Fuente: ${it.source.displayName}. ${thermalExplanation(it.level)}",
                    color = Color(0xFFC6CFDD)
                )
            }
            "fps" -> {
                Text("FPS", color = Color.White, fontWeight = FontWeight.Bold)
                Text(fps?.roundToInt()?.toString() ?: "No disponible", color = Color.White)
                Text(
                    if (fps != null) {
                        "Los FPS indican cuántas imágenes del juego se muestran por segundo."
                    } else {
                        "Este dispositivo o la ruta gráfica del juego no está entregando frames fiables a gfxinfo. No se sustituye el dato por los Hz de la pantalla."
                    },
                    color = Color(0xFFC6CFDD)
                )
            }
        }
    }
}

@Composable
private fun DeviceDiagnosticsCard(
    booster: GameBoosterState,
    onRedetectDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = booster.deviceProfile ?: return
    GlassPanel(modifier) {
        Text("Dispositivo", color = Color.White, fontWeight = FontWeight.Bold)
        Text(profile.brand.ifBlank { profile.manufacturer }, color = Color.White)
        Text(profile.romDisplayName, color = Color(0xFF9DEAF4), fontWeight = FontWeight.SemiBold)
        Text("Perfil: ${profile.adapterDisplayName}", color = Color(0xFFC6CFDD))
        CapabilityLine("Game Mode", booster.capabilities.gameManagerAvailable)
        CapabilityLine("Monitor FPS", booster.capabilities.fpsMonitoringAvailable)
        CapabilityLine("Monitor térmico", booster.capabilities.thermalMonitoringAvailable)
        CapabilityLine("Monitor RAM", booster.capabilities.memoryMonitoringAvailable)
        if (booster.capabilities.vendorGameServiceAvailable) {
            Text(
                "✓ Servicio de juego del fabricante detectado (solo diagnóstico; no se ejecutan comandos OEM no verificados).",
                color = Color(0xFF98F0BC),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (booster.actionsApplied.isNotEmpty()) {
            Text("Optimizaciones activas", color = Color.White, fontWeight = FontWeight.SemiBold)
            booster.actionsApplied.forEach { action ->
                Text(
                    "${if (action.applied) "✓" else "○"} ${action.name}: ${action.detail}",
                    color = if (action.applied) Color(0xFFE8EDF5) else Color(0xFFC6CFDD),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = onRedetectDevice,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0x5C375374),
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("VOLVER A DETECTAR DISPOSITIVO")
        }
    }
}

@Composable
private fun CapabilityLine(name: String, available: Boolean) {
    Text(
        "$name: ${if (available) "Compatible" else "No disponible"}",
        color = if (available) Color(0xFF98F0BC) else Color(0xFFC6CFDD),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun GameCard(
    state: GameLauncherGameUiState,
    busy: Boolean,
    boosterEnabled: Boolean,
    isPresetEnabled: (DensityPreset) -> Boolean,
    isBoosterModeEnabled: (BoosterMode) -> Boolean,
    onSelectProfile: (DensityPreset) -> Unit,
    onSelectBoosterMode: (BoosterMode) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onSetOverlayOpacity: (Int) -> Unit,
    onToggleDefault: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(state.game) { mutableStateOf(false) }
    var overlayOpacityDraft by remember(state.game, state.overlayOpacityPercent) {
        mutableStateOf(state.overlayOpacityPercent.toFloat())
    }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(26.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring())
            .border(1.dp, Color(0x84C8E5FF), shape)
            .clip(shape),
        color = Color(0x9615253B),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    expanded = !expanded
                }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GameIcon(
                    packageName = state.packageName,
                    versionCode = state.versionCode,
                    lastUpdateTime = state.lastUpdateTime,
                    installed = state.installed,
                    fallback = state.applicationName.take(2).uppercase()
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.applicationName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        state.packageName,
                        color = Color(0xFFC6CFDD),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedContent(state.installed, label = "installation") { installed ->
                    Text(
                        if (installed) "● Instalado" else "○ No instalado",
                        color = if (installed) Color(0xFF98F0BC) else Color(0xFFFFD28E),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (!state.enabled) {
                Text("Temporalmente no disponible", color = Color(0xFFFFD28E))
            }

            AnimatedContent(state.selectedProfile, label = "selected-profile") { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${profile.displayName} · ${profile.density} DPI",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        profile.description,
                        color = Color(0xFFC6CFDD),
                        style = MaterialTheme.typography.bodySmall
                    )
                    profileWarning(profile)?.let { warning ->
                        Text(
                            warning,
                            color = Color(0xFFFFD28E),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        if (boosterEnabled) {
                            "Game Booster: ${state.boosterMode.displayName} · ${state.boosterMode.shortDescription}"
                        } else {
                            "Game Booster: desactivado temporalmente"
                        },
                        color = Color(0xFF9DEAF4),
                        style = MaterialTheme.typography.bodySmall
                    )
                    state.lastProfile?.let {
                        Text(
                            "Último usado: ${it.displayName} · ${it.density} DPI",
                            color = Color(0xFFC6CFDD),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.defaultProfile?.let {
                        Text(
                            "Predeterminado: ${it.displayName} · ${it.density} DPI",
                            color = Color(0xFF9DEAF4),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sensibilidad", color = Color.White, fontWeight = FontWeight.Bold)
                    DensityPreset.visualOrder.forEach { preset ->
                        ProfileRow(
                            preset = preset,
                            selected = state.selectedProfile == preset,
                            enabled = isPresetEnabled(preset) && !busy,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectProfile(preset)
                            }
                        )
                    }
                    Text(
                        if (state.defaultProfile == state.selectedProfile) {
                            "★ Quitar como perfil predeterminado"
                        } else {
                            "☆ Usar como perfil predeterminado"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy && isPresetEnabled(state.selectedProfile)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleDefault()
                            }
                            .padding(vertical = 8.dp),
                        color = Color(0xFF9DEAF4),
                        fontWeight = FontWeight.SemiBold
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x55243D59), RoundedCornerShape(20.dp))
                            .border(1.dp, Color(0x55C8E5FF), RoundedCornerShape(20.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Game Booster", color = Color.White, fontWeight = FontWeight.Bold)
                        if (!boosterEnabled) {
                            Text(
                                "Game Booster no disponible en este momento. El juego puede iniciarse normalmente.",
                                color = Color(0xFFFFD28E),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BoosterMode.entries.forEach { mode ->
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
                    }
                }
            }

            PrimaryButton(
                text = "JUGAR",
                enabled = state.canPlay,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlay()
                }
            )
        }
    }
}

@Composable
private fun BoosterModeRow(
    mode: BoosterMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0x84345B7B) else Color(0x42182A42))
            .border(1.dp, if (selected) Color(0xD09DEAF4) else Color(0x50FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Color(0xFF9DEAF4) else Color(0xFFC6CFDD)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                mode.displayName,
                color = if (enabled) Color.White else Color(0x99FFFFFF),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                mode.shortDescription,
                color = Color(0xFF9DEAF4),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                mode.userDescription,
                color = if (enabled) Color(0xFFC6CFDD) else Color(0x80FFFFFF),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ProfileRow(
    preset: DensityPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0x84345B7B) else Color(0x62182A42))
            .border(1.dp, if (selected) Color(0xD09DEAF4) else Color(0x70FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Crossfade(selected, label = "profile-check") { checked ->
            Text(
                if (checked) "●" else "○",
                color = if (checked) Color(0xFF9DEAF4) else Color(0xFFC6CFDD)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                preset.displayName,
                color = if (enabled) Color.White else Color(0x99FFFFFF),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                preset.description,
                color = if (enabled) Color(0xFFC6CFDD) else Color(0x80FFFFFF),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${preset.density} DPI", color = if (enabled) Color.White else Color(0x99FFFFFF))
            Text(
                when {
                    !enabled -> "No disponible"
                    selected -> "Activo"
                    else -> "Inactivo"
                },
                color = when {
                    !enabled -> Color(0xFFFFD28E)
                    selected -> Color(0xFF98F0BC)
                    else -> Color(0xFFC6CFDD)
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun GameIcon(
    packageName: String,
    versionCode: Long,
    lastUpdateTime: Long,
    installed: Boolean,
    fallback: String
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val densityDpi = configuration.densityDpi
    val uiMode = configuration.uiMode
    val repository = remember(context.applicationContext) {
        AppIconRepositoryProvider.get(context.applicationContext)
    }
    val invalidationVersion by repository.invalidationVersion.collectAsState()
    val iconResult by produceState<AppIconResult?>(
        initialValue = null,
        packageName,
        versionCode,
        lastUpdateTime,
        installed,
        densityDpi,
        uiMode,
        invalidationVersion
    ) {
        value = if (installed) repository.getAppIcon(packageName) else AppIconResult.NotFound
    }
    val loaded = iconResult as? AppIconResult.Success
    val scale by animateFloatAsState(
        targetValue = if (loaded == null) 0.98f else 1f,
        animationSpec = tween(180),
        label = "icon-container-scale"
    )
    Box(
        modifier = Modifier
            .widthIn(min = 52.dp, max = 68.dp)
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x68213A57)),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = loaded, label = "game-icon-reload") { success ->
            if (success != null) {
                Image(
                    bitmap = success.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(fallback, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun profileWarning(preset: DensityPreset): String? = when (preset) {
    DensityPreset.ULTRA ->
        "Sensi Ultra utiliza una escala extrema. Mantén ambos botones de volumen durante 2 segundos para restaurar el DPI si lo necesitas."
    DensityPreset.VERY_HIGH ->
        "Sensi Muy Alta utiliza una escala extremadamente reducida. Si tienes algún problema, puedes restaurar el DPI con ambos botones de volumen."
    else -> null
}

private fun ramStatus(ram: RamInfo): String = when (ram.level) {
    RamLevel.EXCELLENT -> "Bien"
    RamLevel.NORMAL -> "Normal"
    RamLevel.LOW -> "Atención"
}

private fun thermalStatus(thermal: ThermalInfo): String = when (thermal.level) {
    ThermalLevel.NORMAL -> "Bien"
    ThermalLevel.WARM -> "Atención"
    ThermalLevel.HOT, ThermalLevel.VERY_HOT -> "Alto"
    ThermalLevel.UNKNOWN -> "No disponible"
}

private fun thermalExplanation(level: ThermalLevel): String = when (level) {
    ThermalLevel.NORMAL -> "El teléfono está funcionando dentro de un rango normal."
    ThermalLevel.WARM -> "El teléfono está templado; Android sigue administrando la temperatura."
    ThermalLevel.HOT -> "El teléfono está caliente y Android puede reducir rendimiento para enfriarlo."
    ThermalLevel.VERY_HOT -> "El teléfono está muy caliente. No se desactivan las protecciones térmicas."
    ThermalLevel.UNKNOWN -> "No pudimos leer un estado térmico fiable, pero puedes seguir jugando."
}

private fun statusColor(status: String): Color = when {
    status.contains("Alto", ignoreCase = true) ||
        status.contains("Bajo", ignoreCase = true) ||
        status.contains("Atención", ignoreCase = true) -> Color(0xFFFFD28E)
    status.contains("No disponible", ignoreCase = true) ||
        status.contains("Sin datos", ignoreCase = true) -> Color(0xFFC6CFDD)
    else -> Color(0xFF98F0BC)
}

private fun formatGiB(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes.toDouble() / 1_073_741_824.0)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(),
        label = "button-press"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .scale(scale),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFAFC8FF),
            contentColor = Color(0xFF071126),
            disabledContainerColor = Color(0x49375374),
            disabledContentColor = Color(0x99FFFFFF)
        )
    ) {
        Text(text, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x9615253B), shape)
            .border(1.dp, Color(0x84C8E5FF), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
