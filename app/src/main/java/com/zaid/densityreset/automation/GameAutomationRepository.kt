package com.zaid.densityreset.automation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface GameAutomationRepository {
    fun observe(): Flow<Map<SupportedGame, GameAutomationPreference>>
    suspend fun read(game: SupportedGame): GameAutomationPreference
    suspend fun setPriorityDndEnabled(game: SupportedGame, enabled: Boolean)
    suspend fun setBrightnessEnabled(game: SupportedGame, enabled: Boolean)
    suspend fun setBrightnessPercent(game: SupportedGame, percent: Int)
    suspend fun setRotationLockEnabled(game: SupportedGame, enabled: Boolean)
    suspend fun setMediaVolumeEnabled(game: SupportedGame, enabled: Boolean)
    suspend fun setMediaVolumePercent(game: SupportedGame, percent: Int)
    suspend fun setScreenRecordingEnabled(game: SupportedGame, enabled: Boolean)
}

class GameAutomationRepositoryImpl(context: Context) : GameAutomationRepository {
    private val appContext = context.applicationContext

    override fun observe(): Flow<Map<SupportedGame, GameAutomationPreference>> =
        appContext.gameAutomationDataStore.data.map { preferences ->
            SupportedGame.entries.associateWith { game -> preferences.toPreference(game) }
        }

    override suspend fun read(game: SupportedGame): GameAutomationPreference =
        appContext.gameAutomationDataStore.data.first().toPreference(game)

    override suspend fun setPriorityDndEnabled(game: SupportedGame, enabled: Boolean) =
        setBoolean(game, "priority_dnd", enabled)

    override suspend fun setBrightnessEnabled(game: SupportedGame, enabled: Boolean) =
        setBoolean(game, "brightness_enabled", enabled)

    override suspend fun setBrightnessPercent(game: SupportedGame, percent: Int) =
        setInt(
            game,
            "brightness_percent",
            percent.coerceIn(
                GameAutomationPreference.MIN_PERCENT,
                GameAutomationPreference.MAX_PERCENT
            )
        )

    override suspend fun setRotationLockEnabled(game: SupportedGame, enabled: Boolean) =
        setBoolean(game, "rotation_lock", enabled)

    override suspend fun setMediaVolumeEnabled(game: SupportedGame, enabled: Boolean) =
        setBoolean(game, "media_volume_enabled", enabled)

    override suspend fun setMediaVolumePercent(game: SupportedGame, percent: Int) =
        setInt(
            game,
            "media_volume_percent",
            percent.coerceIn(
                GameAutomationPreference.MIN_PERCENT,
                GameAutomationPreference.MAX_PERCENT
            )
        )

    override suspend fun setScreenRecordingEnabled(game: SupportedGame, enabled: Boolean) =
        setBoolean(game, "screen_recording", enabled)

    private suspend fun setBoolean(game: SupportedGame, suffix: String, value: Boolean) {
        appContext.gameAutomationDataStore.edit { preferences ->
            preferences[booleanKey(game, suffix)] = value
        }
    }

    private suspend fun setInt(game: SupportedGame, suffix: String, value: Int) {
        appContext.gameAutomationDataStore.edit { preferences ->
            preferences[intKey(game, suffix)] = value
        }
    }
}

private val Context.gameAutomationDataStore by preferencesDataStore(
    name = "game_automation"
)

private fun Preferences.toPreference(game: SupportedGame): GameAutomationPreference =
    GameAutomationPreference(
        priorityDndEnabled = this[booleanKey(game, "priority_dnd")] ?: false,
        brightnessEnabled = this[booleanKey(game, "brightness_enabled")] ?: false,
        brightnessPercent = this[intKey(game, "brightness_percent")]
            ?: GameAutomationPreference.DEFAULT_PERCENT,
        rotationLockEnabled = this[booleanKey(game, "rotation_lock")] ?: false,
        mediaVolumeEnabled = this[booleanKey(game, "media_volume_enabled")] ?: false,
        mediaVolumePercent = this[intKey(game, "media_volume_percent")]
            ?: GameAutomationPreference.DEFAULT_PERCENT,
        screenRecordingEnabled = this[booleanKey(game, "screen_recording")] ?: false
    )

private fun keyPrefix(game: SupportedGame): String = game.name.lowercase()

private fun booleanKey(
    game: SupportedGame,
    suffix: String
): Preferences.Key<Boolean> = booleanPreferencesKey("${keyPrefix(game)}_$suffix")

private fun intKey(
    game: SupportedGame,
    suffix: String
): Preferences.Key<Int> = intPreferencesKey("${keyPrefix(game)}_$suffix")
