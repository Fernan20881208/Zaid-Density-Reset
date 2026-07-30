package com.zaid.densityreset.density

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.densityDataStore by preferencesDataStore(name = "density_state")

data class PersistedDensityState(
    val originalDensity: Int?,
    val lastPreset: DensityPreset?,
    val lastAppliedDensity: Int?,
    val hasOverride: Boolean,
    val lastChangedAt: Long?
)

class DensityPreferencesRepository(context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val originalDensity = intPreferencesKey("original_density")
        val lastPreset = stringPreferencesKey("last_preset")
        val lastAppliedDensity = intPreferencesKey("last_applied_density")
        val hasOverride = booleanPreferencesKey("has_density_override")
        val lastChangedAt = longPreferencesKey("last_changed_at")
    }

    suspend fun read(): PersistedDensityState = appContext.densityDataStore.data
        .map(::toPersistedState)
        .first()

    suspend fun saveOriginalDensityIfAbsent(density: Int) {
        appContext.densityDataStore.edit { preferences ->
            if (preferences[Keys.originalDensity] == null) {
                preferences[Keys.originalDensity] = density
            }
        }
    }

    suspend fun saveAppliedPreset(
        preset: DensityPreset,
        systemState: DensitySystemState,
        changedAt: Long = System.currentTimeMillis()
    ) {
        appContext.densityDataStore.edit { preferences ->
            if (preferences[Keys.originalDensity] == null) {
                preferences[Keys.originalDensity] = systemState.initialDensity
            }
            preferences[Keys.lastPreset] = preset.name
            preferences[Keys.lastAppliedDensity] = systemState.currentDensity
            preferences[Keys.hasOverride] = systemState.hasOverride
            preferences[Keys.lastChangedAt] = changedAt
        }
    }

    suspend fun saveObservedState(
        systemState: DensitySystemState,
        changedAt: Long? = null
    ) {
        appContext.densityDataStore.edit { preferences ->
            if (preferences[Keys.originalDensity] == null) {
                preferences[Keys.originalDensity] = systemState.initialDensity
            }

            val preset = DensityPreset.fromDensity(systemState.currentDensity)
            if (preset != null && systemState.hasOverride) {
                preferences[Keys.lastPreset] = preset.name
            } else {
                preferences.remove(Keys.lastPreset)
            }

            preferences[Keys.lastAppliedDensity] = systemState.currentDensity
            preferences[Keys.hasOverride] = systemState.hasOverride
            if (changedAt != null) {
                preferences[Keys.lastChangedAt] = changedAt
            }
        }
    }

    suspend fun saveReset(
        systemState: DensitySystemState,
        changedAt: Long = System.currentTimeMillis()
    ) {
        appContext.densityDataStore.edit { preferences ->
            if (preferences[Keys.originalDensity] == null) {
                preferences[Keys.originalDensity] = systemState.initialDensity
            }
            preferences.remove(Keys.lastPreset)
            preferences[Keys.lastAppliedDensity] = systemState.currentDensity
            preferences[Keys.hasOverride] = false
            preferences[Keys.lastChangedAt] = changedAt
        }
    }

    private fun toPersistedState(preferences: Preferences): PersistedDensityState {
        val preset = preferences[Keys.lastPreset]
            ?.let { name -> runCatching { DensityPreset.valueOf(name) }.getOrNull() }

        return PersistedDensityState(
            originalDensity = preferences[Keys.originalDensity],
            lastPreset = preset,
            lastAppliedDensity = preferences[Keys.lastAppliedDensity],
            hasOverride = preferences[Keys.hasOverride] ?: false,
            lastChangedAt = preferences[Keys.lastChangedAt]
        )
    }
}
