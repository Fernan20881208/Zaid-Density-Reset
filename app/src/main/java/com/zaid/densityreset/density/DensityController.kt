package com.zaid.densityreset.density

interface DensityController {
    suspend fun getInitialDensity(): Int
    suspend fun getCurrentDensity(): Int
    suspend fun applyDensity(density: Int): Result<Unit>
    suspend fun resetDensity(): Result<Unit>
}

data class DensitySystemState(
    val initialDensity: Int,
    val currentDensity: Int,
    val hasOverride: Boolean,
    val source: DensityReadSource
)

enum class DensityReadSource {
    WINDOW_MANAGER_BINDER,
    WM_COMMAND
}

class DensityControlException(
    val reason: DensityFailureReason,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

enum class DensityFailureReason {
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_PERMISSION_DENIED,
    WINDOW_MANAGER_UNAVAILABLE,
    DENSITY_REJECTED,
    MANUFACTURER_BLOCKED,
    VERIFICATION_FAILED,
    REMOTE_PROCESS_FAILED,
    BINDER_DISCONNECTED,
    UNKNOWN
}
