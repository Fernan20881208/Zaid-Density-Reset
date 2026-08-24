package com.zaid.densityreset.analytics

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ProfilePlaytimeSummary(
    val preset: DensityPreset,
    val totalMillis: Long,
    val sessionCount: Int
)

data class GamePlaytimeSummary(
    val game: SupportedGame,
    val totalMillis: Long = 0L,
    val sessionCount: Int = 0,
    val lastSessionMillis: Long = 0L,
    val lastSessionEndedAt: Long? = null,
    val byProfile: List<ProfilePlaytimeSummary> = emptyList()
)

interface GamePlaytimeRepository {
    fun observe(): Flow<Map<SupportedGame, GamePlaytimeSummary>>

    suspend fun recordSession(
        game: SupportedGame,
        preset: DensityPreset,
        sessionId: Long,
        startedAt: Long,
        endedAt: Long
    ): Boolean
}

class GamePlaytimeRepositoryImpl(context: Context) : GamePlaytimeRepository {
    private val appContext = context.applicationContext

    override fun observe(): Flow<Map<SupportedGame, GamePlaytimeSummary>> =
        appContext.gamePlaytimeDataStore.data.map { preferences ->
            SupportedGame.entries.associateWith { game -> preferences.toSummary(game) }
        }

    override suspend fun recordSession(
        game: SupportedGame,
        preset: DensityPreset,
        sessionId: Long,
        startedAt: Long,
        endedAt: Long
    ): Boolean {
        if (sessionId <= 0L || startedAt <= 0L || endedAt <= startedAt) return false
        var recorded = false
        appContext.gamePlaytimeDataStore.edit { preferences ->
            if (preferences[lastRecordedSessionKey(game)] == sessionId) return@edit
            val duration = sanitizePlaytimeDuration(startedAt, endedAt)
            if (duration <= 0L) return@edit

            preferences[totalKey(game)] =
                safeAdd(preferences[totalKey(game)] ?: 0L, duration)
            preferences[countKey(game)] = safeIncrement(preferences[countKey(game)] ?: 0)
            preferences[lastDurationKey(game)] = duration
            preferences[lastEndedAtKey(game)] = endedAt
            preferences[lastRecordedSessionKey(game)] = sessionId

            preferences[profileTotalKey(game, preset)] = safeAdd(
                preferences[profileTotalKey(game, preset)] ?: 0L,
                duration
            )
            preferences[profileCountKey(game, preset)] = safeIncrement(
                preferences[profileCountKey(game, preset)] ?: 0
            )
            recorded = true
        }
        return recorded
    }
}

internal fun sanitizePlaytimeDuration(startedAt: Long, endedAt: Long): Long =
    (endedAt - startedAt).coerceIn(0L, MAX_SINGLE_SESSION_MILLIS)

private fun safeAdd(current: Long, addition: Long): Long =
    if (Long.MAX_VALUE - current.coerceAtLeast(0L) < addition) {
        Long.MAX_VALUE
    } else {
        current.coerceAtLeast(0L) + addition
    }

private fun safeIncrement(current: Int): Int =
    current.coerceIn(0, Int.MAX_VALUE - 1) + 1

private val Context.gamePlaytimeDataStore by preferencesDataStore(name = "game_playtime")

private fun Preferences.toSummary(game: SupportedGame): GamePlaytimeSummary =
    GamePlaytimeSummary(
        game = game,
        totalMillis = (this[totalKey(game)] ?: 0L).coerceAtLeast(0L),
        sessionCount = (this[countKey(game)] ?: 0).coerceAtLeast(0),
        lastSessionMillis = (this[lastDurationKey(game)] ?: 0L).coerceAtLeast(0L),
        lastSessionEndedAt = this[lastEndedAtKey(game)],
        byProfile = DensityPreset.entries.mapNotNull { preset ->
            val total = (this[profileTotalKey(game, preset)] ?: 0L).coerceAtLeast(0L)
            val count = (this[profileCountKey(game, preset)] ?: 0).coerceAtLeast(0)
            if (total == 0L && count == 0) null else {
                ProfilePlaytimeSummary(preset, total, count)
            }
        }.sortedByDescending { it.totalMillis }
    )

private fun prefix(game: SupportedGame): String = game.name.lowercase()

private fun totalKey(game: SupportedGame): Preferences.Key<Long> =
    longPreferencesKey("${prefix(game)}_total_ms")

private fun countKey(game: SupportedGame): Preferences.Key<Int> =
    intPreferencesKey("${prefix(game)}_session_count")

private fun lastDurationKey(game: SupportedGame): Preferences.Key<Long> =
    longPreferencesKey("${prefix(game)}_last_duration_ms")

private fun lastEndedAtKey(game: SupportedGame): Preferences.Key<Long> =
    longPreferencesKey("${prefix(game)}_last_ended_at")

private fun lastRecordedSessionKey(game: SupportedGame): Preferences.Key<Long> =
    longPreferencesKey("${prefix(game)}_last_recorded_session")

private fun profileTotalKey(
    game: SupportedGame,
    preset: DensityPreset
): Preferences.Key<Long> = longPreferencesKey(
    "${prefix(game)}_${preset.name.lowercase()}_total_ms"
)

private fun profileCountKey(
    game: SupportedGame,
    preset: DensityPreset
): Preferences.Key<Int> = intPreferencesKey(
    "${prefix(game)}_${preset.name.lowercase()}_session_count"
)

private const val MAX_SINGLE_SESSION_MILLIS = 24L * 60L * 60L * 1_000L
