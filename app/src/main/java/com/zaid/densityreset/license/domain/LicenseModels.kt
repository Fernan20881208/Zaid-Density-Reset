package com.zaid.densityreset.license.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class LicenseErrorCode {
    INVALID_KEY,
    LICENSE_EXPIRED,
    LICENSE_REVOKED,
    LICENSE_DISABLED,
    DEVICE_LIMIT,
    DEVICE_MISMATCH,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    APP_VERSION_BLOCKED,
    TOKEN_EXPIRED,
    INVALID_SESSION,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): LicenseErrorCode =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

sealed interface LicenseState {
    data object Checking : LicenseState
    data object NoLicense : LicenseState
    data class Active(
        val expiresAt: Instant?,
        val offlineGrace: Boolean = false,
        val lastValidatedAt: Instant? = null
    ) : LicenseState
    data class Expired(val expiresAt: Instant?) : LicenseState
    data object Revoked : LicenseState
    data object Disabled : LicenseState
    data object NetworkRequired : LicenseState
    data object AppVersionBlocked : LicenseState
    data class Error(val message: String) : LicenseState
}

data class LicenseResult(
    val success: Boolean,
    val status: String? = null,
    val expiresAt: Instant? = null,
    val tokenExpiresAt: Instant? = null,
    val licenseToken: String? = null,
    val offlineGraceHours: Int? = null,
    val code: LicenseErrorCode? = null,
    val message: String? = null,
    val offlineGrace: Boolean = false
)

interface LicenseRepository {
    suspend fun activate(key: String): LicenseResult
    suspend fun validate(): LicenseResult
    suspend fun logout()
    fun observeLicenseState(): Flow<LicenseState>
}
