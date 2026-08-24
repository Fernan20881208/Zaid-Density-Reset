package com.zaid.densityreset.appearance

import android.content.Context

enum class AppAppearanceMode(val persistedValue: String) {
    LIQUID_GLASS("liquid_glass"),
    AMOLED("amoled");

    companion object {
        fun fromPersistedValue(value: String?): AppAppearanceMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIQUID_GLASS
    }
}

object AppAppearancePreferences {
    private const val FILE_NAME = "density_reset_preferences"
    private const val KEY_APPEARANCE_MODE = "appearance_mode"

    fun get(context: Context): AppAppearanceMode = AppAppearanceMode.fromPersistedValue(
        preferences(context).getString(KEY_APPEARANCE_MODE, null)
    )

    fun set(context: Context, mode: AppAppearanceMode) {
        preferences(context)
            .edit()
            .putString(KEY_APPEARANCE_MODE, mode.persistedValue)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
