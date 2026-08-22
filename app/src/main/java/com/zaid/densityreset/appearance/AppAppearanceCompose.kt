package com.zaid.densityreset.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val LocalAppAppearanceMode = staticCompositionLocalOf {
    AppAppearanceMode.LIQUID_GLASS
}

@Composable
fun DensityResetAppearance(
    mode: AppAppearanceMode,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppAppearanceMode provides mode, content = content)
}

fun appearanceBackground(mode: AppAppearanceMode): Brush = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Brush.verticalGradient(
        listOf(Color(0xFF07101F), Color(0xFF172B47), Color(0xFF080D17))
    )
    AppAppearanceMode.AMOLED -> Brush.verticalGradient(
        listOf(Color.Black, Color.Black)
    )
}

fun appearancePanelColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x9615253B)
    AppAppearanceMode.AMOLED -> Color(0xFF050505)
}

fun appearanceSubcardColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x55243D59)
    AppAppearanceMode.AMOLED -> Color(0xFF0A0A0A)
}

fun appearanceBorderColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x84C8E5FF)
    AppAppearanceMode.AMOLED -> Color(0xFF252525)
}
