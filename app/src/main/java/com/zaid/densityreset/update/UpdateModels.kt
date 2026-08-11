package com.zaid.densityreset.update

import java.io.File
import java.time.Instant

data class AppRelease(
    val releaseId: Long,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkAssetName: String,
    val sha256: String,
    val mandatory: Boolean,
    val minVersionCode: Long,
    val releaseNotes: String?,
    val publishedAt: Instant
)

sealed interface UpdateResult {
    data class UpToDate(val latest: AppRelease?) : UpdateResult
    data class Available(val release: AppRelease) : UpdateResult
    data class Failure(
        val message: String,
        val httpStatus: Int? = null,
        val retryable: Boolean = true
    ) : UpdateResult
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int?
    ) : DownloadState
    data class Downloaded(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
    data object Cancelled : DownloadState
}

data class VerifiedUpdate(
    val release: AppRelease,
    val file: File,
    val packageName: String,
    val versionCode: Long
)

sealed interface ApkVerificationResult {
    data class Valid(val update: VerifiedUpdate) : ApkVerificationResult
    data class Invalid(val message: String) : ApkVerificationResult
}
