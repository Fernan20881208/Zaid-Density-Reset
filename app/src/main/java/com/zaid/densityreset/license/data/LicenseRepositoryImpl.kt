package com.zaid.densityreset.license.data

import android.content.Context
import com.zaid.densityreset.BuildConfig
import com.zaid.densityreset.license.domain.LicenseErrorCode
import com.zaid.densityreset.license.domain.LicenseRepository
import com.zaid.densityreset.license.domain.LicenseResult
import com.zaid.densityreset.license.domain.LicenseState
import com.zaid.densityreset.license.network.LicenseApiClient
import com.zaid.densityreset.license.network.LicenseNetworkException
import com.zaid.densityreset.license.util.LicenseKeyFormatter
import com.zaid.densityreset.license.util.LicensePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

class LicenseRepositoryImpl(
    private val context: Context,
    private val apiClient: LicenseApiClient = LicenseApiClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider = AndroidDeviceIdentityProvider(context),
    private val secureStore: SecureLicenseStore = SecureLicenseStore(context),
    private val preferences: LicensePreferences = LicensePreferences(context)
) : LicenseRepository {

    private val mutableState = MutableStateFlow<LicenseState>(LicenseState.Checking)

    override fun observeLicenseState(): Flow<LicenseState> = mutableState.asStateFlow()

    override suspend fun activate(key: String): LicenseResult {
        val normalizedKey = LicenseKeyFormatter.normalize(key)
        if (!LicenseKeyFormatter.isValid(normalizedKey)) {
            val result = failure(LicenseErrorCode.INVALID_KEY)
            mutableState.value = LicenseState.NoLicense
            return result
        }

        mutableState.value = LicenseState.Checking
        val deviceHash = deviceIdentityProvider.getDeviceHash()
        return try {
            val response = apiClient.activate(
                key = normalizedKey,
                deviceHash = deviceHash,
                appVersionCode = BuildConfig.VERSION_CODE,
                packageName = context.packageName
            )
            handleServerResponse(response.body, deviceHash)
        } catch (_: LicenseNetworkException) {
            mutableState.value = LicenseState.NoLicense
            failure(LicenseErrorCode.NETWORK_ERROR)
        }
    }

    override suspend fun validate(): LicenseResult {
        val session = secureStore.read()
        if (session == null) {
            mutableState.value = LicenseState.NoLicense
            return failure(LicenseErrorCode.INVALID_SESSION)
        }

        mutableState.value = LicenseState.Checking
        return try {
            val response = apiClient.validate(
                licenseToken = session.token,
                deviceHash = session.deviceHash,
                appVersionCode = BuildConfig.VERSION_CODE,
                packageName = context.packageName
            )
            handleServerResponse(response.body, session.deviceHash)
        } catch (_: LicenseNetworkException) {
            useOfflineGraceIfPossible(session)
        }
    }

    override suspend fun logout() {
        secureStore.clear()
        preferences.clear()
        mutableState.value = LicenseState.NoLicense
    }

    private suspend fun handleServerResponse(
        body: org.json.JSONObject,
        deviceHash: String
    ): LicenseResult {
        if (!body.optBoolean("success", false)) {
            val code = LicenseErrorCode.fromWire(body.optString("code", null))
            val expiresAt = parseInstant(body.optString("expiresAt", null))
            when (code) {
                LicenseErrorCode.LICENSE_EXPIRED -> {
                    preferences.markStatus("expired", expiresAt?.toEpochMilli())
                    mutableState.value = LicenseState.Expired(expiresAt)
                }
                LicenseErrorCode.LICENSE_REVOKED -> {
                    preferences.markStatus("revoked")
                    mutableState.value = LicenseState.Revoked
                }
                LicenseErrorCode.LICENSE_DISABLED -> {
                    preferences.markStatus("disabled")
                    mutableState.value = LicenseState.Disabled
                }
                LicenseErrorCode.APP_VERSION_BLOCKED -> {
                    mutableState.value = LicenseState.AppVersionBlocked
                }
                LicenseErrorCode.TOKEN_EXPIRED,
                LicenseErrorCode.INVALID_SESSION -> {
                    secureStore.clear()
                    preferences.clear()
                    mutableState.value = LicenseState.NoLicense
                }
                else -> {
                    val local = preferences.read()
                    mutableState.value = if (local.lastSuccessfulValidationEpochMillis == null) {
                        LicenseState.NoLicense
                    } else {
                        LicenseState.Error(LicensePolicy.userMessage(code))
                    }
                }
            }
            return failure(code, expiresAt)
        }

        val status = body.optString("status", "active")
        val expiresAt = parseInstant(body.optString("expiresAt", null))
        val tokenExpiresAt = parseInstant(body.optString("tokenExpiresAt", null))
        val offlineGraceHours = body.optInt(
            "offlineGraceHours",
            BuildConfig.LICENSE_OFFLINE_GRACE_HOURS.toInt()
        ).coerceIn(0, 168)
        val token = body.optString("licenseToken", null)
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: secureStore.read()?.token
            ?: return failure(LicenseErrorCode.INVALID_SESSION)

        val now = Instant.now()
        secureStore.save(
            SecureLicenseSession(
                token = token,
                deviceHash = deviceHash,
                tokenExpiresAtEpochMillis = tokenExpiresAt?.toEpochMilli()
            )
        )
        preferences.markSuccessfulValidation(
            status = status,
            expiresAtEpochMillis = expiresAt?.toEpochMilli(),
            validatedAtEpochMillis = now.toEpochMilli(),
            offlineGraceHours = offlineGraceHours
        )
        mutableState.value = LicenseState.Active(
            expiresAt = expiresAt,
            offlineGrace = false,
            lastValidatedAt = now
        )
        return LicenseResult(
            success = true,
            status = status,
            expiresAt = expiresAt,
            tokenExpiresAt = tokenExpiresAt,
            licenseToken = token,
            offlineGraceHours = offlineGraceHours
        )
    }

    private suspend fun useOfflineGraceIfPossible(
        session: SecureLicenseSession
    ): LicenseResult {
        val local = preferences.read()
        val now = System.currentTimeMillis()
        val graceHours = local.offlineGraceHours
            ?: BuildConfig.LICENSE_OFFLINE_GRACE_HOURS.toInt()
        val canUseOffline = LicensePolicy.canUseOffline(
            nowEpochMillis = now,
            lastSuccessfulValidationEpochMillis = local.lastSuccessfulValidationEpochMillis,
            licenseExpiresAtEpochMillis = local.expiresAtEpochMillis,
            tokenExpiresAtEpochMillis = session.tokenExpiresAtEpochMillis,
            gracePeriodMillis = graceHours * 60L * 60L * 1_000L
        )

        if (canUseOffline) {
            val expiresAt = local.expiresAtEpochMillis?.let(Instant::ofEpochMilli)
            mutableState.value = LicenseState.Active(
                expiresAt = expiresAt,
                offlineGrace = true,
                lastValidatedAt = local.lastSuccessfulValidationEpochMillis?.let(Instant::ofEpochMilli)
            )
            return LicenseResult(
                success = true,
                status = local.status ?: "active",
                expiresAt = expiresAt,
                offlineGraceHours = graceHours,
                offlineGrace = true,
                message = "Acceso temporal sin conexión."
            )
        }

        if (local.expiresAtEpochMillis != null && now >= local.expiresAtEpochMillis) {
            val expiresAt = Instant.ofEpochMilli(local.expiresAtEpochMillis)
            mutableState.value = LicenseState.Expired(expiresAt)
            return failure(LicenseErrorCode.LICENSE_EXPIRED, expiresAt)
        }

        mutableState.value = LicenseState.NetworkRequired
        return failure(LicenseErrorCode.NETWORK_ERROR)
    }

    private fun failure(
        code: LicenseErrorCode,
        expiresAt: Instant? = null
    ): LicenseResult = LicenseResult(
        success = false,
        expiresAt = expiresAt,
        code = code,
        message = LicensePolicy.userMessage(code)
    )

    private fun parseInstant(value: String?): Instant? =
        value
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
