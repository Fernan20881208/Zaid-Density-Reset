package com.zaid.densityreset.startup

import android.app.Application
import com.zaid.densityreset.BuildConfig
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.service.DpiGameSessionService
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.update.AppRelease
import com.zaid.densityreset.update.UpdateManager
import com.zaid.densityreset.update.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.math.max

object StartupCoordinator {

    private val gateMutex = Mutex()
    private val _gate = MutableStateFlow<StartupGate>(StartupGate.Checking)
    val gate: StateFlow<StartupGate> = _gate.asStateFlow()

    @Volatile
    private var initialized = false

    private lateinit var application: Application
    private lateinit var sessionRepository: GameSessionRepositoryImpl

    fun initialize(app: Application) {
        if (initialized) return
        application = app
        sessionRepository = GameSessionRepositoryImpl(app.applicationContext)
        initialized = true
    }

    suspend fun refresh(forceNetwork: Boolean = false): StartupGate {
        check(initialized) { "StartupCoordinator is not initialized." }
        return gateMutex.withLock {
            _gate.value = StartupGate.Checking

            if (!recoverCriticalDensityIfNeeded()) {
                return@withLock setGate(
                    StartupGate.Error(
                        "Se detectó una sesión de DPI pendiente y no fue posible restaurarla. " +
                            "Inicia Shizuku y pulsa Reintentar antes de continuar."
                    )
                )
            }

            val cached = RemoteConfigManager.loadCachedSnapshot()
            val remoteResult = RemoteConfigManager.refresh()
            val liveRemoteConfig = remoteResult.getOrNull()
            val effectiveConfig = liveRemoteConfig ?: cached.config
            val currentVersion = BuildConfig.VERSION_CODE.toLong()

            val githubResult = if (effectiveConfig.githubUpdatesEnabled) {
                UpdateManager.checkForUpdates(force = forceNetwork)
            } else {
                null
            }

            val cachedBlock = updateRequiredByRemote(currentVersion, cached.config)
            val liveBlock = liveRemoteConfig?.let {
                updateRequiredByRemote(currentVersion, it)
            }

            when (githubResult) {
                is UpdateResult.Available -> {
                    return@withLock setGate(StartupGate.UpdateRequired(githubResult.release))
                }

                is UpdateResult.UpToDate -> {
                    val knownRelease = githubResult.latest
                    val requiredCode = liveBlock ?: cachedBlock
                    if (requiredCode != null) {
                        val release = knownRelease
                            ?.takeIf { it.versionCode > currentVersion }
                            ?: syntheticRelease(requiredCode, effectiveConfig)
                        return@withLock setGate(StartupGate.UpdateRequired(release))
                    }
                }

                is UpdateResult.Failure -> {
                    if (liveRemoteConfig != null) {
                        val latest = liveRemoteConfig.latestVersionCode
                        val requiredCode = liveBlock
                        if (requiredCode != null) {
                            return@withLock setGate(
                                StartupGate.UpdateRequired(
                                    syntheticRelease(requiredCode, liveRemoteConfig)
                                )
                            )
                        }
                        if (latest == null) {
                            return@withLock setGate(
                                StartupGate.Error(
                                    "No se pudo comprobar la versión actual. Necesitas conexión a Internet " +
                                        "para verificar las actualizaciones."
                                )
                            )
                        }
                    } else {
                        if (cachedBlock != null) {
                            return@withLock setGate(
                                StartupGate.UpdateRequired(
                                    syntheticRelease(cachedBlock, cached.config)
                                )
                            )
                        }
                        return@withLock setGate(
                            StartupGate.Error(
                                "No se pudo comprobar la versión actual. Necesitas conexión a Internet " +
                                    "para verificar las actualizaciones."
                            )
                        )
                    }
                }

                null -> {
                    val requiredCode = liveBlock ?: cachedBlock
                    if (requiredCode != null) {
                        return@withLock setGate(
                            StartupGate.UpdateRequired(
                                syntheticRelease(requiredCode, effectiveConfig)
                            )
                        )
                    }
                    if (liveRemoteConfig == null) {
                        return@withLock setGate(
                            StartupGate.Error(
                                "No se pudo validar la configuración remota. Pulsa Reintentar."
                            )
                        )
                    }
                }
            }

            if (effectiveConfig.maintenanceMode) {
                return@withLock setGate(
                    StartupGate.Maintenance(
                        effectiveConfig.maintenanceMessage
                            ?: "Estamos realizando mantenimiento. Inténtalo nuevamente más tarde."
                    )
                )
            }

            val license = LicenseManager.checkOnLaunch()
            if (!license.success) {
                return@withLock setGate(StartupGate.LicenseRequired)
            }

            setGate(StartupGate.Ready)
        }
    }

    fun currentGate(): StartupGate = _gate.value

    fun isReady(): Boolean = _gate.value is StartupGate.Ready

    fun resetForRecheck() {
        UpdateManager.clearSessionCheck()
        _gate.value = StartupGate.Checking
    }

    private suspend fun recoverCriticalDensityIfNeeded(): Boolean {
        val session = sessionRepository.read()
        if (!session.sessionActive) return true

        val now = System.currentTimeMillis()
        val expired = session.restoreAt?.let { it <= now } ?: true
        val incomplete = session.currentStep != SessionStep.SESSION_ACTIVE
        if (!expired && !incomplete) return true

        DpiGameSessionService.recover(application.applicationContext)
        return withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
            sessionRepository.state.first { state -> !state.sessionActive }
            true
        } ?: false
    }

    private fun updateRequiredByRemote(
        currentVersion: Long,
        config: RemoteAppConfig
    ): Long? {
        val blocked = currentVersion in config.blockedVersionCodes
        val minBlocked = currentVersion < config.minSupportedVersionCode
        val latestBlocked = config.latestVersionCode?.let { currentVersion < it } ?: false
        val forceBlocked = config.forceUpdate &&
            config.latestVersionCode?.let { currentVersion < it } == true

        if (!blocked && !minBlocked && !latestBlocked && !forceBlocked) return null
        val next = currentVersion + 1L
        return listOfNotNull(
            config.latestVersionCode,
            config.minSupportedVersionCode.takeIf { it > currentVersion },
            next.takeIf { blocked }
        ).maxOrNull() ?: next
    }

    private fun syntheticRelease(
        requiredCode: Long,
        config: RemoteAppConfig
    ): AppRelease {
        val target = max(requiredCode, config.latestVersionCode ?: requiredCode)
        return AppRelease(
            releaseId = -target,
            versionCode = target,
            versionName = "versionCode $target",
            apkUrl = "",
            apkAssetName = "",
            sha256 = "0".repeat(64),
            mandatory = true,
            minVersionCode = max(config.minSupportedVersionCode, target),
            releaseNotes = "La versión instalada fue bloqueada por la configuración remota. " +
                "Vuelve a comprobar GitHub para descargar la Release oficial.",
            publishedAt = Instant.EPOCH
        )
    }

    private fun setGate(value: StartupGate): StartupGate {
        _gate.value = value
        return value
    }

    private const val RECOVERY_TIMEOUT_MILLIS = 12_000L
}
