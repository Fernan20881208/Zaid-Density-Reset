package com.zaid.densityreset.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.flow.Flow

object UpdateManager {

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionResult: UpdateResult? = null

    private lateinit var application: Application
    private lateinit var repository: UpdateRepository
    private lateinit var downloader: UpdateDownloadManager
    private lateinit var verifier: ApkVerifier

    fun initialize(app: Application) {
        if (initialized) return
        application = app
        repository = GitHubUpdateRepository(app.applicationContext)
        downloader = UpdateDownloadManager(app.applicationContext)
        verifier = ApkVerifier(app.applicationContext)
        downloader.cleanupAfterSuccessfulUpdate(BuildConfig.VERSION_CODE.toLong())
        initialized = true
    }

    suspend fun checkForUpdates(force: Boolean = false): UpdateResult {
        check(initialized) { "UpdateManager is not initialized." }
        if (!force) sessionResult?.let { return it }
        return repository.checkForUpdates().also { sessionResult = it }
    }

    suspend fun downloadUpdate(release: AppRelease): Flow<DownloadState> {
        check(initialized) { "UpdateManager is not initialized." }
        return repository.downloadUpdate(release)
    }

    suspend fun verifyDownloaded(
        release: AppRelease,
        file: java.io.File
    ): ApkVerificationResult {
        val result = verifier.verify(release, file)
        when (result) {
            is ApkVerificationResult.Valid -> downloader.markVerified(release, file)
            is ApkVerificationResult.Invalid -> downloader.invalidate(file)
        }
        return result
    }

    suspend fun verifiedDownloadedFile(release: AppRelease): VerifiedUpdate? {
        val file = downloader.verifiedFile(release) ?: return null
        return when (val result = verifier.verify(release, file)) {
            is ApkVerificationResult.Valid -> result.update
            is ApkVerificationResult.Invalid -> {
                downloader.invalidate(file)
                null
            }
        }
    }

    suspend fun installVerified(
        context: Context,
        update: VerifiedUpdate
    ): InstallLaunchResult {
        val reverified = verifier.verify(update.release, update.file)
        if (reverified !is ApkVerificationResult.Valid) {
            downloader.invalidate(update.file)
            return InstallLaunchResult.Failed(
                (reverified as? ApkVerificationResult.Invalid)?.message
                    ?: "No se pudo volver a verificar el APK."
            )
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            ).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallLaunchResult.PermissionRequested
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.update-files",
            update.file
        )
        val installer = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(installer)
            InstallLaunchResult.InstallerOpened
        }.getOrElse {
            InstallLaunchResult.Failed("Android no pudo abrir el instalador de paquetes.")
        }
    }

    fun clearSessionCheck() {
        sessionResult = null
    }

    sealed interface InstallLaunchResult {
        data object InstallerOpened : InstallLaunchResult
        data object PermissionRequested : InstallLaunchResult
        data class Failed(val message: String) : InstallLaunchResult
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
