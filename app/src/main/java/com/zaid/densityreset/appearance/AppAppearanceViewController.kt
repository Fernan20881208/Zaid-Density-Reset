package com.zaid.densityreset.appearance

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowInsetsControllerCompat
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.zaid.densityreset.R

object AppAppearanceViewController {
    const val TAG_HEADER = "appearance_glass_header"
    const val TAG_PANEL = "appearance_glass_panel"
    const val TAG_SUBCARD = "appearance_glass_subcard"

    fun applyActivityTheme(activity: Activity, mode: AppAppearanceMode) {
        activity.setTheme(
            if (mode == AppAppearanceMode.AMOLED) {
                R.style.Theme_DensityReset_Amoled
            } else {
                R.style.Theme_DensityReset
            }
        )
    }

    fun applyWindow(activity: Activity, mode: AppAppearanceMode) {
        val black = Color.BLACK
        activity.window.statusBarColor =
            if (mode == AppAppearanceMode.AMOLED) black else Color.TRANSPARENT
        activity.window.navigationBarColor =
            if (mode == AppAppearanceMode.AMOLED) black else Color.rgb(9, 17, 30)
        WindowInsetsControllerCompat(
            activity.window,
            activity.window.decorView
        ).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    fun applyBackdrop(
        root: View,
        backgroundImage: ImageView,
        scrim: View,
        mode: AppAppearanceMode
    ) {
        if (mode == AppAppearanceMode.AMOLED) {
            root.setBackgroundColor(Color.BLACK)
            backgroundImage.setImageDrawable(null)
            backgroundImage.visibility = View.GONE
            scrim.visibility = View.GONE
        } else {
            root.setBackgroundResource(R.drawable.bg_liquid_background)
            backgroundImage.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
        }
    }

    fun applyTaggedSurfaces(
        root: View,
        backdropSource: View,
        mode: AppAppearanceMode
    ) {
        val surfaces = mutableListOf<View>()
        collectTaggedSurfaces(root, surfaces)
        surfaces.forEach { surface ->
            if (mode == AppAppearanceMode.AMOLED) {
                applyAmoledSurface(surface)
            } else {
                wrapInLiquidGlass(surface, backdropSource)
            }
        }
    }

    private fun collectTaggedSurfaces(view: View, output: MutableList<View>) {
        val appearanceTag = view.tag as? String
        if (appearanceTag == TAG_HEADER || appearanceTag == TAG_PANEL || appearanceTag == TAG_SUBCARD) {
            output += view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTaggedSurfaces(view.getChildAt(index), output)
            }
        }
    }

    private fun applyAmoledSurface(surface: View) {
        surface.setBackgroundResource(
            when (surface.tag) {
                TAG_HEADER -> R.drawable.bg_amoled_header
                TAG_SUBCARD -> R.drawable.bg_amoled_subcard
                else -> R.drawable.bg_amoled_panel
            }
        )
    }

    private fun wrapInLiquidGlass(surface: View, sourceView: View) {
        val parent = surface.parent as? ViewGroup ?: return
        if (parent is LiquidGlassView) return

        val index = parent.indexOfChild(surface)
        if (index < 0) return
        val outerLayoutParams = surface.layoutParams
        val innerWidth = outerLayoutParams.width
        val innerHeight = outerLayoutParams.height
        val density = surface.resources.displayMetrics.density
        val radiusDp = if (surface.tag == TAG_SUBCARD) 22f else 28f

        parent.removeViewAt(index)
        val glass = LiquidGlassView(surface.context).apply {
            layoutParams = outerLayoutParams
            tag = "appearance_liquid_glass_wrapper"
            cornerRadius = radiusDp * density
            material = GlassMaterial.REGULAR
            refractionHeight = 64f * density
            bevelWidth = 12f * density
            dispersionStrength = 0.10f
            blurAmount = 0.055f
            saturation = 125f
            aberrationIntensity = 1.4f
            enableDynamicBackground = true
            enableSensorHighlight = surface.tag == TAG_HEADER
            enablePressEffect = false
            enableAdaptiveTint = false
            accessibilityMode = GlassAccessibilityMode.AUTO
            enableShadow = false
            collectFrameStats = false
            backdropSource = sourceView
        }

        surface.background = null
        surface.layoutParams = FrameLayout.LayoutParams(innerWidth, innerHeight)
        glass.addView(surface)
        parent.addView(glass, index)
    }
}
