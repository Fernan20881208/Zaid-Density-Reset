package com.zaid.densityreset.license.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.licenseDataStore by preferencesDataStore(
    name = "license_state"
)

data class LicenseLocalState(
    val status: String?,
    val expiresAtEpochMillis: Long?,
    val lastSuccessfulValidationEpochMillis: Long?
)

class LicensePreferences(
    private val context: Context
) {
    suspend fun read(): LicenseLocalState {
        val values = context.licenseDataStore.data.first()
        return LicenseLocalState(
            status = values[STATUS],
            expiresAtEpochMillis = values[EXPIRES_AT],
            lastSuccessfulValidationEpochMillis = values[LAST_SUCCESSFUL_VALIDATION]
        )
    }

    suspend fun markSuccessfulValidation(
        status: String,
        expiresAtEpochMillis: Long?,
        validatedAtEpochMillis: Long
    ) {
        context.licenseDataStore.edit { values ->
            values[STATUS] = status
            if (expiresAtEpochMillis == null) {
                values.remove(EXPIRES_AT)
            } else {
                values[EXPIRES_AT] = expiresAtEpochMillis
            }
            values[LAST_SUCCESSFUL_VALIDATION] = validatedAtEpochMillis
        }
    }

    suspend fun markStatus(
        status: String,
        expiresAtEpochMillis: Long? = null
    ) {
        context.licenseDataStore.edit { values ->
            values[STATUS] = status
            if (expiresAtEpochMillis == null) {
                values.remove(EXPIRES_AT)
            } else {
                values[EXPIRES_AT] = expiresAtEpochMillis
            }
        }
    }

    suspend fun clear() {
        context.licenseDataStore.edit { it.clear() }
    }

    private companion object {
        val STATUS = stringPreferencesKey("status")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val LAST_SUCCESSFUL_VALIDATION = longPreferencesKey("last_successful_validation")
    }
}
