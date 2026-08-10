package com.zaid.densityreset.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class UpdateDownloadManager(context: Context) {

    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun download(release: AppRelease): Flow<DownloadState> = flow {
        val existing = downloadedFile(release)
        if (existing != null) {
            emit(DownloadState.Downloaded(existing))
            return@flow
        }

        val downloadId = activeDownloadId(release) ?: enqueue(release)
        while (true) {
            val cursor = downloadManager.query(
                DownloadManager.Query().setFilterById(downloadId)
            )
            cursor.use {
                if (!it.moveToFirst()) {
                    clearDownloadState()
                    emit(DownloadState.Cancelled)
                    return@flow
                }

                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ).coerceAtLeast(0L)
                val total = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val file = targetFile(release)
                        if (!file.isFile || file.length() <= 0L) {
                            clearDownloadState()
                            emit(DownloadState.Failed("La descarga finalizó sin un APK válido."))
                        } else {
                            emit(DownloadState.Downloaded(file))
                        }
                        return@flow
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        clearDownloadState()
                        emit(DownloadState.Failed("La descarga falló (código $reason)."))
                        return@flow
                    }

                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        val percent = if (total > 0L) {
                            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            null
                        }
                        emit(
                            DownloadState.Downloading(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progressPercent = percent
                            )
                        )
                    }
                }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    fun verifiedFile(release: AppRelease): File? {
        val verifiedReleaseId = preferences.getLong(KEY_VERIFIED_RELEASE_ID, -1L)
        if (verifiedReleaseId != release.releaseId) return null
        return downloadedFile(release)
    }

    fun markVerified(release: AppRelease, file: File) {
        preferences.edit()
            .putLong(KEY_RELEASE_ID, release.releaseId)
            .putString(KEY_FILE_NAME, file.name)
            .putLong(KEY_VERIFIED_RELEASE_ID, release.releaseId)
            .apply()
    }

    fun invalidate(file: File?) {
        file?.let { runCatching { if (it.exists()) it.delete() } }
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id > 0L) runCatching { downloadManager.remove(id) }
        clearDownloadState()
    }

    fun cleanupAfterSuccessfulUpdate(currentVersionCode: Long) {
        val downloadedVersion = preferences.getLong(KEY_DOWNLOADED_VERSION_CODE, -1L)
        if (downloadedVersion <= 0L || currentVersionCode < downloadedVersion) return
        preferences.getString(KEY_FILE_NAME, null)?.let { name ->
            val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (base != null) runCatching { File(base, name).delete() }
        }
        clearDownloadState()
    }

    private fun enqueue(release: AppRelease): Long {
        require(release.apkUrl.startsWith("https://")) { "El APK debe descargarse mediante HTTPS." }
        val target = targetFile(release)
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Density Reset ${release.versionName}")
            .setDescription("Descargando actualización oficial")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                release.apkAssetName
            )

        val id = downloadManager.enqueue(request)
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putLong(KEY_RELEASE_ID, release.releaseId)
            .putLong(KEY_DOWNLOADED_VERSION_CODE, release.versionCode)
            .putString(KEY_FILE_NAME, release.apkAssetName)
            .remove(KEY_VERIFIED_RELEASE_ID)
            .apply()
        return id
    }

    private fun activeDownloadId(release: AppRelease): Long? {
        if (preferences.getLong(KEY_RELEASE_ID, -1L) != release.releaseId) return null
        return preferences.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it > 0L }
    }

    private fun downloadedFile(release: AppRelease): File? {
        if (preferences.getLong(KEY_RELEASE_ID, -1L) != release.releaseId) return null
        val storedName = preferences.getString(KEY_FILE_NAME, null) ?: return null
        if (storedName != release.apkAssetName) return null
        return targetFile(release).takeIf { it.isFile && it.length() > 0L }
    }

    private fun targetFile(release: AppRelease): File {
        val base = requireNotNull(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ) { "No existe directorio de descargas de la aplicación." }
        val safeName = release.apkAssetName.substringAfterLast('/')
        require(safeName == release.apkAssetName && safeName.endsWith(".apk", true)) {
            "Nombre de APK inválido."
        }
        val target = File(base, safeName)
        require(target.canonicalFile.parentFile == base.canonicalFile) {
            "Ruta de descarga inválida."
        }
        return target
    }

    private fun clearDownloadState() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_RELEASE_ID)
            .remove(KEY_DOWNLOADED_VERSION_CODE)
            .remove(KEY_FILE_NAME)
            .remove(KEY_VERIFIED_RELEASE_ID)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "density_update_download"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_RELEASE_ID = "release_id"
        const val KEY_DOWNLOADED_VERSION_CODE = "downloaded_version_code"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_VERIFIED_RELEASE_ID = "verified_release_id"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val POLL_INTERVAL_MILLIS = 650L
    }
}
