package com.zaid.densityreset.license.util

import com.zaid.densityreset.license.domain.LicenseErrorCode

object LicensePolicy {
    fun canUseOffline(
        nowEpochMillis: Long,
        lastSuccessfulValidationEpochMillis: Long?,
        licenseExpiresAtEpochMillis: Long?,
        tokenExpiresAtEpochMillis: Long?,
        gracePeriodMillis: Long
    ): Boolean {
        val lastValidation = lastSuccessfulValidationEpochMillis ?: return false
        if (nowEpochMillis - lastValidation > gracePeriodMillis) return false
        if (
            licenseExpiresAtEpochMillis != null &&
            nowEpochMillis >= licenseExpiresAtEpochMillis
        ) return false
        if (
            tokenExpiresAtEpochMillis != null &&
            nowEpochMillis >= tokenExpiresAtEpochMillis
        ) return false
        return true
    }

    fun userMessage(code: LicenseErrorCode): String = when (code) {
        LicenseErrorCode.INVALID_KEY -> "La key introducida no es válida."
        LicenseErrorCode.LICENSE_EXPIRED -> "Esta licencia ha expirado."
        LicenseErrorCode.LICENSE_REVOKED -> "Esta licencia fue revocada."
        LicenseErrorCode.LICENSE_DISABLED -> "Esta licencia está deshabilitada temporalmente."
        LicenseErrorCode.DEVICE_LIMIT -> "Esta key alcanzó el límite de dispositivos permitidos."
        LicenseErrorCode.DEVICE_MISMATCH -> "Esta key está vinculada a otro dispositivo."
        LicenseErrorCode.RATE_LIMITED -> "Demasiados intentos. Inténtalo nuevamente más tarde."
        LicenseErrorCode.APP_VERSION_BLOCKED -> "Esta versión de la aplicación ya no está autorizada. Actualiza Density Reset."
        LicenseErrorCode.TOKEN_EXPIRED,
        LicenseErrorCode.INVALID_SESSION -> "La sesión de licencia ya no es válida. Introduce tu key nuevamente."
        LicenseErrorCode.NETWORK_ERROR -> "No fue posible conectar con el servidor de licencias."
        LicenseErrorCode.SERVER_ERROR -> "El servidor de licencias no pudo completar la solicitud."
        LicenseErrorCode.UNKNOWN -> "No fue posible verificar la licencia."
    }
}
