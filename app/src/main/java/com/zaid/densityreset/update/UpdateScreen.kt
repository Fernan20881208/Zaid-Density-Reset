package com.zaid.densityreset.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaid.densityreset.BuildConfig
import kotlin.math.max

@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit
) {
    BackHandler(enabled = true) { }

    val release = state.release ?: return
    val background = Brush.verticalGradient(
        listOf(Color(0xFF07101F), Color(0xFF172B47), Color(0xFF080D17))
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Density Reset",
                    color = Color(0xFFE8EDF5),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = "Nueva actualización disponible",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            UpdateGlassCard {
                Text(
                    text = "Debes actualizar Density Reset para continuar.",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Instalada", color = Color(0xFFC6CFDD))
                        Text(
                            BuildConfig.VERSION_NAME,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("→", color = Color(0xFF9DEAF4), fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Nueva", color = Color(0xFFC6CFDD))
                        Text(
                            release.versionName,
                            color = Color(0xFF9DEAF4),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "versionCode ${BuildConfig.VERSION_CODE} → ${release.versionCode}",
                    color = Color(0xFFC6CFDD),
                    style = MaterialTheme.typography.bodySmall
                )

                val notes = sanitizeReleaseNotes(release.releaseNotes)
                if (notes.isNotBlank()) {
                    Text(
                        text = "Novedades",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = notes,
                        color = Color(0xFFE8EDF5),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                AnimatedVisibility(
                    visible = state.stage == UpdateStage.DOWNLOADING ||
                        state.stage == UpdateStage.VERIFYING ||
                        state.stage == UpdateStage.READY,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(120))
                ) {
                    UpdateProgress(state)
                }

                state.message?.let { message ->
                    Text(
                        text = message,
                        color = if (state.stage == UpdateStage.ERROR) {
                            Color(0xFFFFBBB4)
                        } else {
                            Color(0xFFE8EDF5)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                AnimatedContent(
                    targetState = state.stage,
                    label = "update-action"
                ) { stage ->
                    when (stage) {
                        UpdateStage.AVAILABLE -> {
                            UpdateButton(
                                text = if (release.apkUrl.isBlank()) {
                                    "REINTENTAR COMPROBACIÓN"
                                } else {
                                    "ACTUALIZAR AHORA"
                                },
                                onClick = if (release.apkUrl.isBlank()) onRetryCheck else onDownload
                            )
                        }
                        UpdateStage.DOWNLOADING -> {
                            UpdateButton(text = "DESCARGANDO…", enabled = false, onClick = {})
                        }
                        UpdateStage.VERIFYING -> {
                            UpdateButton(text = "VERIFICANDO…", enabled = false, onClick = {})
                        }
                        UpdateStage.READY -> {
                            UpdateButton(text = "INSTALAR ACTUALIZACIÓN", onClick = onInstall)
                        }
                        UpdateStage.ERROR -> {
                            UpdateButton(
                                text = if (release.apkUrl.isBlank()) {
                                    "REINTENTAR COMPROBACIÓN"
                                } else {
                                    "REINTENTAR"
                                },
                                onClick = if (release.apkUrl.isBlank()) onRetryCheck else onDownload
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}

@Composable
private fun UpdateProgress(state: UpdateUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val animated by animateFloatAsState(
            targetValue = (state.progressPercent ?: 0).coerceIn(0, 100) / 100f,
            animationSpec = tween(220),
            label = "download-progress"
        )
        if (state.stage == UpdateStage.DOWNLOADING) {
            if (state.progressPercent != null) {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF9DEAF4),
                    trackColor = Color(0x36FFFFFF)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF9DEAF4),
                    trackColor = Color(0x36FFFFFF)
                )
            }
            Text(
                text = buildString {
                    append(state.progressPercent?.let { "$it% · " }.orEmpty())
                    append(formatBytes(state.downloadedBytes))
                    if (state.totalBytes > 0) append(" / ${formatBytes(state.totalBytes)}")
                },
                color = Color(0xFFE8EDF5),
                style = MaterialTheme.typography.bodySmall
            )
        } else if (state.stage == UpdateStage.VERIFYING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF9DEAF4),
                trackColor = Color(0x36FFFFFF)
            )
            Text("Verificando SHA-256, package, versión y firma…", color = Color(0xFFE8EDF5))
        } else if (state.stage == UpdateStage.READY) {
            Text(
                text = "✓ Instalación lista",
                color = Color(0xFF98F0BC),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UpdateGlassCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x9615253B), shape)
            .border(1.dp, Color(0x84C8E5FF), shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun UpdateButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
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

internal fun sanitizeReleaseNotes(markdown: String?): String {
    if (markdown.isNullOrBlank()) return ""
    val linkRegex = Regex("\\[([^]]+)]\\([^)]*\\)")
    val htmlRegex = Regex("<[^>]+>")
    return markdown
        .lineSequence()
        .take(80)
        .map { raw ->
            var line = raw.trim().take(300)
            line = htmlRegex.replace(line, "")
            line = linkRegex.replace(line, "$1")
            line = line.replace("**", "").replace("__", "").replace("`", "")
            line = line.replace(Regex("^#{1,6}\\s*"), "")
            line = line.replace(Regex("^[-*+]\\s+"), "• ")
            line
        }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(8_000)
}

private fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    val mib = safe / (1024.0 * 1024.0)
    return if (mib >= 1.0) "%.1f MB".format(mib) else "%.0f KB".format(safe / 1024.0)
}
