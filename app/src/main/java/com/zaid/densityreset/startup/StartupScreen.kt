package com.zaid.densityreset.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StartupScreen(
    gate: StartupGate,
    onRetry: () -> Unit
) {
    BackHandler(enabled = gate !is StartupGate.Ready) { }
    val background = Brush.verticalGradient(
        listOf(Color(0xFF07101F), Color(0xFF172B47), Color(0xFF080D17))
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(targetState = gate, label = "startup-gate") { current ->
            when (current) {
                StartupGate.Checking -> GateCard {
                    CircularProgressIndicator(color = Color(0xFF9DEAF4))
                    Text(
                        text = "Density Reset",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Comprobando configuración, versión y acceso…",
                        color = Color(0xFFE8EDF5)
                    )
                }

                is StartupGate.Maintenance -> GateCard {
                    Text(
                        text = "Density Reset",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Estamos realizando mantenimiento.",
                        color = Color(0xFFFFD28E),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = current.message, color = Color(0xFFE8EDF5))
                    Text(
                        text = "Inténtalo nuevamente más tarde.",
                        color = Color(0xFFC6CFDD)
                    )
                    RetryButton(onRetry)
                }

                is StartupGate.Error -> GateCard {
                    Text(
                        text = "Density Reset",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = current.message, color = Color(0xFFFFBBB4))
                    RetryButton(onRetry)
                }

                StartupGate.LicenseRequired -> GateCard {
                    CircularProgressIndicator(color = Color(0xFF9DEAF4))
                    Text("Abriendo validación de licencia…", color = Color(0xFFE8EDF5))
                }

                is StartupGate.UpdateRequired,
                StartupGate.Ready -> Unit
            }
        }
    }
}

@Composable
private fun GateCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(20.dp)
            .background(Color(0x9615253B), shape)
            .border(1.dp, Color(0x84C8E5FF), shape)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    Button(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFAFC8FF),
            contentColor = Color(0xFF071126)
        )
    ) {
        Text("REINTENTAR", fontWeight = FontWeight.Bold)
    }
}
