package com.zaid.densityreset.gameprofile.domain

import com.zaid.densityreset.density.DensityPreset

data class DensitySnapshot(
    val physicalDensity: Int,
    val effectiveDensity: Int,
    val hadOverride: Boolean,
    val previousOverrideDensity: Int?
)

enum class SessionStep {
    IDLE,
    VALIDATING,
    SAVING_DENSITY,
    CLOSING_GAME,
    APPLYING_DENSITY,
    VERIFYING_DENSITY,
    OPENING_GAME,
    SESSION_ACTIVE,
    RESTORING_DENSITY,
    COMPLETED,
    ERROR
}

data class GameSessionState(
    val sessionActive: Boolean = false,
    val selectedGame: SupportedGame? = null,
    val selectedPreset: DensityPreset? = null,
    val targetDensity: Int? = null,
    val sessionStartedAt: Long? = null,
    val restoreAt: Long? = null,
    val currentStep: SessionStep = SessionStep.IDLE,
    val snapshot: DensitySnapshot? = null,
    val errorMessage: String? = null,
    val lastResultMessage: String? = null,
    val lastResultAt: Long? = null
)

sealed interface GameSessionResult {
    data class Success(val message: String) : GameSessionResult
    data class Failure(val message: String) : GameSessionResult
}

data class GameProfileUiState(
    val selectedGame: SupportedGame? = null,
    val selectedPreset: DensityPreset? = null,
    val installedGames: Set<SupportedGame> = emptySet(),
    val shizukuReady: Boolean = false,
    val sessionActive: Boolean = false,
    val currentStep: SessionStep = SessionStep.IDLE,
    val secondsRemaining: Int? = null,
    val activeDensity: Int? = null,
    val previousDensity: Int? = null,
    val errorMessage: String? = null,
    val canStart: Boolean = false
)
