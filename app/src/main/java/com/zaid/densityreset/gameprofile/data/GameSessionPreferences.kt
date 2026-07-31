package com.zaid.densityreset.gameprofile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.gameSessionDataStore by preferencesDataStore(
    name = "game_profile_session"
)

internal object GameSessionPreferenceKeys {
    val sessionActive = booleanPreferencesKey("session_active")
    val selectedGamePackage = stringPreferencesKey("selected_game_package")
    val selectedPreset = stringPreferencesKey("selected_preset")
    val targetDensity = intPreferencesKey("target_density")
    val sessionStartedAt = longPreferencesKey("session_started_at")
    val restoreAt = longPreferencesKey("restore_at")
    val currentSessionStep = stringPreferencesKey("current_session_step")

    val snapshotPhysicalDensity = intPreferencesKey("snapshot_physical_density")
    val snapshotEffectiveDensity = intPreferencesKey("snapshot_effective_density")
    val snapshotHadOverride = booleanPreferencesKey("snapshot_had_override")
    val snapshotPreviousOverride = intPreferencesKey("snapshot_previous_override")

    val errorMessage = stringPreferencesKey("session_error_message")
    val lastResultMessage = stringPreferencesKey("last_result_message")
    val lastResultAt = longPreferencesKey("last_result_at")
}
