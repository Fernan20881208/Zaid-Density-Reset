package com.zaid.densityreset.booster

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.zaid.densityreset.appearance.AppAppearanceMode
import com.zaid.densityreset.appearance.AppAppearancePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lightweight in-game HUD backed by TYPE_APPLICATION_OVERLAY. It only renders
 * values that already exist in GameBoosterRuntime; it never invents FPS or
 * temperature data and it disappears as soon as the booster session ends.
 */
class GameStatsOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: LinearLayout? = null
    private var modeText: TextView? = null
    private var fpsText: TextView? = null
    private var ramText: TextView? = null
    private var batteryText: TextView? = null
    private var thermalText: TextView? = null
    private var collectorJob: Job? = null

    fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    fun start(opacityPercent: Int = DEFAULT_OVERLAY_OPACITY_PERCENT): Boolean {
        if (!canDraw()) return false
        val normalizedOpacity = normalizeOverlayOpacity(opacityPercent)
        val palette = gameStatsOverlayPalette(AppAppearancePreferences.get(appContext))
        if (root == null && !attachWindow(normalizedOpacity, palette)) return false
        root?.let { applyPalette(it, palette) }
        root?.alpha = normalizedOpacity / 100f

        collectorJob?.cancel()
        collectorJob = scope.launch {
            GameBoosterRuntime.state.collectLatest { state ->
                if (state.active) render(state) else clearText()
            }
        }
        return true
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        root?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        root = null
        modeText = null
        fpsText = null
        ramText = null
        batteryText = null
        thermalText = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun attachWindow(
        opacityPercent: Int,
        palette: GameStatsOverlayPalette
    ): Boolean = runCatching {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            alpha = normalizeOverlayOpacity(opacityPercent) / 100f
        }

        fun line(sizeSp: Float = 11f, bold: Boolean = false): TextView =
            TextView(appContext).apply {
                setTextColor(palette.textColor)
                textSize = sizeSp
                includeFontPadding = false
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }.also(container::addView)

        modeText = line(sizeSp = 10f, bold = true)
        fpsText = line()
        ramText = line()
        batteryText = line()
        thermalText = line()
        applyPalette(container, palette)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(96)
            title = "Density Reset Performance HUD"
        }

        windowManager.addView(container, params)
        root = container
        true
    }.getOrDefault(false)

    private fun applyPalette(
        container: LinearLayout,
        palette: GameStatsOverlayPalette
    ) {
        container.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            palette.backgroundColors.copyOf()
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), palette.borderColor)
        }
        modeText?.setTextColor(palette.accentColor)
        listOf(fpsText, ramText, batteryText, thermalText).forEach { text ->
            text?.setTextColor(palette.textColor)
        }
    }

    private fun render(state: GameBoosterState) {
        modeText?.text = "ZAID · ${state.mode?.displayName ?: "Game Booster"}"

        val fps = state.monitor.fps?.fps
        fpsText?.text = if (fps != null) {
            "FPS   ${fps.roundToInt()}"
        } else {
            "FPS   —"
        }

        val ram = state.monitor.ram
        ramText?.text = if (ram != null) {
            "RAM   ${formatGiB(ram.availableBytes)} / ${formatGiB(ram.totalBytes)} GB"
        } else {
            "RAM   —"
        }

        val battery = state.monitor.battery
        batteryText?.text = if (battery != null) {
            "BAT   ${battery.percent}%${if (battery.charging) " · cargando" else ""}"
        } else {
            "BAT   —"
        }

        val thermal = state.monitor.thermal
        thermalText?.text = if (thermal != null) {
            thermal.temperatureCelsius?.let {
                "TEMP  ${it.roundToInt()} °C · ${thermal.level.displayName}"
            } ?: "TEMP  ${thermal.level.displayName}"
        } else {
            "TEMP  —"
        }
    }

    private fun clearText() {
        modeText?.text = "ZAID · Game Booster"
        fpsText?.text = "FPS   —"
        ramText?.text = "RAM   —"
        batteryText?.text = "BAT   —"
        thermalText?.text = "TEMP  —"
    }

    private fun formatGiB(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes.toDouble() / GIB)

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}

internal data class GameStatsOverlayPalette(
    val backgroundColors: IntArray,
    val borderColor: Int,
    val textColor: Int,
    val accentColor: Int
)

internal fun gameStatsOverlayPalette(mode: AppAppearanceMode): GameStatsOverlayPalette =
    when (mode) {
        AppAppearanceMode.LIQUID_GLASS -> GameStatsOverlayPalette(
            backgroundColors = intArrayOf(0xED15253B.toInt(), 0xED08111E.toInt()),
            borderColor = 0xB49DEAF4.toInt(),
            textColor = 0xFFFFFFFF.toInt(),
            accentColor = 0xFF9DEAF4.toInt()
        )
        AppAppearanceMode.AMOLED -> GameStatsOverlayPalette(
            backgroundColors = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt()),
            borderColor = 0xFF2A2A2A.toInt(),
            textColor = 0xFFFFFFFF.toInt(),
            accentColor = 0xFF9DEAF4.toInt()
        )
    }
