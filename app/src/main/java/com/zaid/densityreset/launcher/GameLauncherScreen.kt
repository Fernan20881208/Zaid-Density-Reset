package com.zaid.densityreset.launcher

import android.graphics.drawable.Drawable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.SupportedGame

@Composable
fun GameLauncherScreen(
    state: GameLauncherUiState,
    isPresetEnabled: (DensityPreset) -> Boolean,
    onSelectProfile: (SupportedGame, DensityPreset) -> Unit,
    onToggleDefault: (SupportedGame) -> Unit,
    onPlay: (SupportedGame) -> Unit,
    onRestore: () -> Unit,
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
                        "Elige tu juego y perfil. La restauración utiliza el sistema de sesión existente.",
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
                    GlassPanel(Modifier.padding(horizontal = 16.dp)) {
                        Text("Sesión activa", color = Color(0xFF98F0BC), fontWeight = FontWeight.Bold)
                        val game = state.session.selectedGame
                        val preset = state.session.selectedPreset
                        Text(
                            listOfNotNull(
                                game?.displayName,
                                preset?.let { "${it.displayName} · ${state.session.targetDensity ?: it.density} DPI" }
                            ).joinToString(" · "),
                            color = Color.White
                        )
                        PrimaryButton("RESTAURAR DPI AHORA", true, onRestore)
                    }
                }
            }

            items(state.games, key = { it.packageName }) { game ->
                GameCard(
                    state = game,
                    busy = state.busy,
                    isPresetEnabled = isPresetEnabled,
                    onSelectProfile = { onSelectProfile(game.game, it) },
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
private fun GameCard(
    state: GameLauncherGameUiState,
    busy: Boolean,
    isPresetEnabled: (DensityPreset) -> Boolean,
    onSelectProfile: (DensityPreset) -> Unit,
    onToggleDefault: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(state.game) { mutableStateOf(false) }
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
                GameIcon(state.icon, state.applicationName.take(2).uppercase())
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DensityPreset.entries.forEach { preset ->
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
            Text(if (checked) "●" else "○", color = if (checked) Color(0xFF9DEAF4) else Color(0xFFC6CFDD))
        }
        Text(
            preset.displayName,
            modifier = Modifier.weight(1f),
            color = if (enabled) Color.White else Color(0x99FFFFFF),
            fontWeight = FontWeight.SemiBold
        )
        Column(horizontalAlignment = Alignment.End) {
            Text("${preset.density} DPI", color = if (enabled) Color.White else Color(0x99FFFFFF))
            if (!enabled) {
                Text(
                    "Temporalmente no disponible",
                    color = Color(0xFFFFD28E),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

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
private fun GameIcon(icon: Drawable?, fallback: String) {
    val bitmap = remember(icon) {
        icon?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    }
    val scale by animateFloatAsState(
        targetValue = if (bitmap == null) 0.94f else 1f,
        animationSpec = tween(220),
        label = "icon-scale"
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
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize())
        } else {
            Text(fallback, color = Color.White, fontWeight = FontWeight.Bold)
        }
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
