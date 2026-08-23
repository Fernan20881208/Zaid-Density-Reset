package com.zaid.densityreset.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.zaid.densityreset.R
import com.zaid.densityreset.util.ImageAssets

val LocalAppAppearanceMode = staticCompositionLocalOf {
    AppAppearanceMode.LIQUID_GLASS
}

private val LocalLiquidGlassBackdropSource = staticCompositionLocalOf<View?> { null }

private val liquidGlassBackdropBitmap: Bitmap? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    runCatching {
        val bytes = Base64.decode(ImageAssets.BACKGROUND_BASE64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

/**
 * Provides one detailed Android View as the shared backdrop source for every
 * QWEA0 LiquidGlassView rendered inside the Compose hierarchy.
 */
@Composable
fun DensityResetAppearance(
    mode: AppAppearanceMode,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (mode == AppAppearanceMode.AMOLED) Color.Black else Color(0xFF07101F))
    ) {
        val backdropSource = if (mode == AppAppearanceMode.LIQUID_GLASS) {
            val context = LocalContext.current
            val backdrop = remember(context) {
                ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.bg_liquid_background)
                    liquidGlassBackdropBitmap?.let(::setImageBitmap)
                }
            }
            AndroidView(
                factory = { backdrop },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x42040B16))
            )
            backdrop
        } else {
            null
        }

        CompositionLocalProvider(
            LocalAppAppearanceMode provides mode,
            LocalLiquidGlassBackdropSource provides backdropSource,
            content = content
        )
    }
}

/**
 * A non-interactive QWEA0 glass surface. Compose owns all gestures and content;
 * the Android View is restricted to rendering the real refractive backdrop.
 */
@Composable
fun Qwea0LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    sensorHighlight: Boolean = false
) {
    val mode = LocalAppAppearanceMode.current
    val source = LocalLiquidGlassBackdropSource.current
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val refractionHeightPx = with(density) { 56.dp.toPx() }
    val bevelWidthPx = with(density) { 10.dp.toPx() }

    if (mode == AppAppearanceMode.LIQUID_GLASS && source != null) {
        AndroidView(
            factory = { context -> PassiveLiquidGlassHost(context) },
            update = { host ->
                val glass = host.glass
                glass.cornerRadius = cornerRadiusPx
                glass.material = GlassMaterial.REGULAR
                glass.refractionHeight = refractionHeightPx
                glass.bevelWidth = bevelWidthPx
                glass.dispersionStrength = 0.10f
                glass.blurAmount = 0.05f
                glass.saturation = 128f
                glass.aberrationIntensity = 1.2f
                glass.enableDynamicBackground = true
                glass.enableSensorHighlight = sensorHighlight
                glass.enablePressEffect = false
                glass.enableAdaptiveTint = false
                glass.accessibilityMode = GlassAccessibilityMode.AUTO
                glass.enableShadow = false
                glass.enableOptimizedCapture = true
                glass.globalDownsampleFactor =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 0.75f else 0.5f
                glass.collectFrameStats = false
                glass.backdropSource = source
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                appearancePanelColor(mode),
                RoundedCornerShape(cornerRadius)
            )
        )
    }
}

/** Shared top-level panel used by all Compose screens. */
@Composable
fun DensityResetGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    val mode = LocalAppAppearanceMode.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        Qwea0LiquidGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadius = cornerRadius
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(appearancePanelOverlayColor(mode), shape)
                .border(1.dp, appearanceBorderColor(mode), shape)
                .padding(contentPadding),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

fun appearanceBackground(mode: AppAppearanceMode): Brush = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Brush.verticalGradient(
        listOf(Color(0xB307101F), Color(0xA8172B47), Color(0xBC080D17))
    )
    AppAppearanceMode.AMOLED -> Brush.verticalGradient(
        listOf(Color.Black, Color.Black)
    )
}

fun appearancePanelColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x9615253B)
    AppAppearanceMode.AMOLED -> Color(0xFF050505)
}

private fun appearancePanelOverlayColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x2415253B)
    AppAppearanceMode.AMOLED -> Color.Transparent
}

fun appearanceSubcardColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x55243D59)
    AppAppearanceMode.AMOLED -> Color(0xFF0A0A0A)
}

fun appearanceBorderColor(mode: AppAppearanceMode): Color = when (mode) {
    AppAppearanceMode.LIQUID_GLASS -> Color(0x84C8E5FF)
    AppAppearanceMode.AMOLED -> Color(0xFF252525)
}

private class PassiveLiquidGlassHost(context: Context) : FrameLayout(context) {
    val glass = LiquidGlassView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(glass)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}
