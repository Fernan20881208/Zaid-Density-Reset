package com.zaid.densityreset.booster

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
