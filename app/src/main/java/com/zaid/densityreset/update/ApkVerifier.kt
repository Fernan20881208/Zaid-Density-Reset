package com.zaid.densityreset.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ApkVerifier(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    suspend fun verify(
        release: AppRelease,
        file: File
    ): ApkVerificationResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile || file.length() <= 0L) {
                return@runCatching invalid("El archivo de actualización no existe.")
            }

            val actualHash = sha256(file)
            if (!actualHash.equals(release.sha256, ignoreCase = true)) {
                return@runCatching invalid("El SHA-256 no coincide con la Release oficial.")
            }

            val archiveInfo = archivePackageInfo(file)
                ?: return@runCatching invalid("Android no pudo leer el APK descargado.")

            if (archiveInfo.packageName != BuildConfig.APPLICATION_ID) {
                return@runCatching invalid("El package name del APK no corresponde a Density Reset.")
            }

            val archiveVersion = PackageInfoCompat.getLongVersionCode(archiveInfo)
            val currentVersion = BuildConfig.VERSION_CODE.toLong()
            if (archiveVersion <= currentVersion) {
                return@runCatching invalid("El APK descargado no es una versión superior.")
            }
            if (archiveVersion != release.versionCode) {
                return@runCatching invalid("El versionCode del APK no coincide con update.json.")
            }

            val installedInfo = installedPackageInfo()
            if (!hasCompatibleSigner(installedInfo, archiveInfo)) {
                return@runCatching invalid("La firma del APK no coincide con la aplicación instalada.")
            }

            ApkVerificationResult.Valid(
                VerifiedUpdate(
                    release = release,
                    file = file,
                    packageName = archiveInfo.packageName,
                    versionCode = archiveVersion
                )
            )
        }.getOrElse {
            invalid("No se pudo verificar la actualización.")
        }
    }

    private fun invalid(message: String): ApkVerificationResult.Invalid =
        ApkVerificationResult.Invalid(message)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                BuildConfig.APPLICATION_ID,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasCompatibleSigner(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val installedDigests = installed.signatures.orEmpty()
                .map(::certificateDigest)
                .toSet()
            val archiveDigests = archive.signatures.orEmpty()
                .map(::certificateDigest)
                .toSet()
            return archiveDigests.isNotEmpty() && archiveDigests == installedDigests
        }

        val installedInfo = installed.signingInfo ?: return false
        val archiveInfo = archive.signingInfo ?: return false
        val installedHistory = installedInfo.signingCertificateHistory
            .map(::certificateDigest)
            .toSet()
        val archiveCurrentSigners = archiveInfo.apkContentsSigners
            .map(::certificateDigest)
            .toSet()

        return archiveCurrentSigners.isNotEmpty() &&
            archiveCurrentSigners.all { it in installedHistory }
    }

    private fun certificateDigest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
