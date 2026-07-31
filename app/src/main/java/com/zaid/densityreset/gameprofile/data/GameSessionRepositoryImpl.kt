package com.zaid.densityreset.gameprofile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.DensitySnapshot
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface GameSessionRepository {
    val state: Flow<GameSessionState>

    suspend fun read(): GameSessionState

    suspend fun beginSession(
        game: SupportedGame,
        preset: DensityPreset,
        snapshot: DensitySnapshot,
        startedAt: Long
    )

    suspend fun updateStep(
        step: SessionStep,
        errorMessage: String? = null
    )

    suspend fun markSessionActive(restoreAt: Long)

    suspend fun markRestorationFailure(message: String)

    suspend fun finishSession(message: String)

    suspend fun failAndClear(message: String)

    suspend fun consumeLastResult()
}

class GameSessionRepositoryImpl(context: Context) : GameSessionRepository {

    private val appContext = context.applicationContext

    override val state: Flow<GameSessionState> = appContext.gameSessionDataStore.data
        .map(::toState)

    override suspend fun read(): GameSessionState = state.first()

    override suspend fun beginSession(
        game: SupportedGame,
        preset: DensityPreset,
        snapshot: DensitySnapshot,
        startedAt: Long
    ) {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences[GameSessionPreferenceKeys.sessionActive] = true
            preferences[GameSessionPreferenceKeys.selectedGamePackage] = game.packageName
            preferences[GameSessionPreferenceKeys.selectedPreset] = preset.name
            preferences[GameSessionPreferenceKeys.targetDensity] = preset.density
            preferences[GameSessionPreferenceKeys.sessionStartedAt] = startedAt
            preferences.remove(GameSessionPreferenceKeys.restoreAt)
            preferences[GameSessionPreferenceKeys.currentSessionStep] =
                SessionStep.SAVING_DENSITY.name

            preferences[GameSessionPreferenceKeys.snapshotPhysicalDensity] =
                snapshot.physicalDensity
            preferences[GameSessionPreferenceKeys.snapshotEffectiveDensity] =
                snapshot.effectiveDensity
            preferences[GameSessionPreferenceKeys.snapshotHadOverride] =
                snapshot.hadOverride
            if (snapshot.previousOverrideDensity != null) {
                preferences[GameSessionPreferenceKeys.snapshotPreviousOverride] =
                    snapshot.previousOverrideDensity
            } else {
                preferences.remove(GameSessionPreferenceKeys.snapshotPreviousOverride)
            }

            preferences.remove(GameSessionPreferenceKeys.errorMessage)
            preferences.remove(GameSessionPreferenceKeys.lastResultMessage)
            preferences.remove(GameSessionPreferenceKeys.lastResultAt)
        }
    }

    override suspend fun updateStep(
        step: SessionStep,
        errorMessage: String?
    ) {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences[GameSessionPreferenceKeys.currentSessionStep] = step.name
            if (errorMessage.isNullOrBlank()) {
                preferences.remove(GameSessionPreferenceKeys.errorMessage)
            } else {
                preferences[GameSessionPreferenceKeys.errorMessage] = errorMessage
            }
        }
    }

    override suspend fun markSessionActive(restoreAt: Long) {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences[GameSessionPreferenceKeys.sessionActive] = true
            preferences[GameSessionPreferenceKeys.restoreAt] = restoreAt
            preferences[GameSessionPreferenceKeys.currentSessionStep] =
                SessionStep.SESSION_ACTIVE.name
            preferences.remove(GameSessionPreferenceKeys.errorMessage)
        }
    }

    override suspend fun markRestorationFailure(message: String) {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences[GameSessionPreferenceKeys.sessionActive] = true
            preferences[GameSessionPreferenceKeys.currentSessionStep] =
                SessionStep.ERROR.name
            preferences[GameSessionPreferenceKeys.errorMessage] = message
        }
    }

    override suspend fun finishSession(message: String) {
        clearTemporaryState(
            message = message,
            finalStep = SessionStep.COMPLETED
        )
    }

    override suspend fun failAndClear(message: String) {
        clearTemporaryState(
            message = message,
            finalStep = SessionStep.ERROR
        )
    }

    override suspend fun consumeLastResult() {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences.remove(GameSessionPreferenceKeys.lastResultMessage)
            preferences.remove(GameSessionPreferenceKeys.lastResultAt)
        }
    }

    private suspend fun clearTemporaryState(
        message: String,
        finalStep: SessionStep
    ) {
        appContext.gameSessionDataStore.edit { preferences ->
            preferences[GameSessionPreferenceKeys.sessionActive] = false
            preferences[GameSessionPreferenceKeys.currentSessionStep] = finalStep.name
            preferences[GameSessionPreferenceKeys.lastResultMessage] = message
            preferences[GameSessionPreferenceKeys.lastResultAt] =
                System.currentTimeMillis()

            preferences.remove(GameSessionPreferenceKeys.selectedGamePackage)
            preferences.remove(GameSessionPreferenceKeys.selectedPreset)
            preferences.remove(GameSessionPreferenceKeys.targetDensity)
            preferences.remove(GameSessionPreferenceKeys.sessionStartedAt)
            preferences.remove(GameSessionPreferenceKeys.restoreAt)
            preferences.remove(GameSessionPreferenceKeys.snapshotPhysicalDensity)
            preferences.remove(GameSessionPreferenceKeys.snapshotEffectiveDensity)
            preferences.remove(GameSessionPreferenceKeys.snapshotHadOverride)
            preferences.remove(GameSessionPreferenceKeys.snapshotPreviousOverride)
            preferences.remove(GameSessionPreferenceKeys.errorMessage)
        }
    }

    private fun toState(preferences: Preferences): GameSessionState {
        val game = SupportedGame.fromPackageName(
            preferences[GameSessionPreferenceKeys.selectedGamePackage]
        )
        val preset = preferences[GameSessionPreferenceKeys.selectedPreset]
            ?.let { name ->
                runCatching { DensityPreset.valueOf(name) }.getOrNull()
            }
        val step = preferences[GameSessionPreferenceKeys.currentSessionStep]
            ?.let { name ->
                runCatching { SessionStep.valueOf(name) }.getOrNull()
            }
            ?: SessionStep.IDLE

        val physical = preferences[GameSessionPreferenceKeys.snapshotPhysicalDensity]
        val effective = preferences[GameSessionPreferenceKeys.snapshotEffectiveDensity]
        val hadOverride = preferences[GameSessionPreferenceKeys.snapshotHadOverride]
        val snapshot = if (
            physical != null &&
            effective != null &&
            hadOverride != null
        ) {
            DensitySnapshot(
                physicalDensity = physical,
                effectiveDensity = effective,
                hadOverride = hadOverride,
                previousOverrideDensity =
                    preferences[GameSessionPreferenceKeys.snapshotPreviousOverride]
            )
        } else {
            null
        }

        return GameSessionState(
            sessionActive =
                preferences[GameSessionPreferenceKeys.sessionActive] ?: false,
            selectedGame = game,
            selectedPreset = preset,
            targetDensity = preferences[GameSessionPreferenceKeys.targetDensity],
            sessionStartedAt = preferences[GameSessionPreferenceKeys.sessionStartedAt],
            restoreAt = preferences[GameSessionPreferenceKeys.restoreAt],
            currentStep = step,
            snapshot = snapshot,
            errorMessage = preferences[GameSessionPreferenceKeys.errorMessage],
            lastResultMessage =
                preferences[GameSessionPreferenceKeys.lastResultMessage],
            lastResultAt = preferences[GameSessionPreferenceKeys.lastResultAt]
        )
    }
}
