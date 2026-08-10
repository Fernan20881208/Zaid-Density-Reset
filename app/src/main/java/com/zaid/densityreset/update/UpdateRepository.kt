package com.zaid.densityreset.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant

interface UpdateRepository {
    suspend fun checkForUpdates(): UpdateResult
    suspend fun downloadUpdate(release: AppRelease): Flow<DownloadState>
}

class GitHubUpdateRepository(context: Context) : UpdateRepository {

    private val appContext = context.applicationContext
    private val cache = UpdateCacheStorage(appContext)
    private val downloader = UpdateDownloadManager(appContext)

    override suspend fun checkForUpdates(): UpdateResult = withContext(Dispatchers.IO) {
        val owner = BuildConfig.GITHUB_OWNER
        val repository = BuildConfig.GITHUB_REPOSITORY
        val endpoint = "https://api.github.com/repos/$owner/$repository/releases/latest"

        try {
            val releaseResponse = getJson(endpoint)
            if (releaseResponse.status !in 200..299) {
                return@withContext failureForStatus(releaseResponse.status)
            }

            val releaseJson = JSONObject(releaseResponse.body)
            if (releaseJson.optBoolean("draft", false) || releaseJson.optBoolean("prerelease", false)) {
                return@withContext UpdateResult.Failure(
                    "GitHub no devolvió una Release estable válida.",
                    retryable = true
                )
            }

            val releaseId = releaseJson.optLong("id", -1L)
            if (releaseId <= 0L) {
                return@withContext UpdateResult.Failure("Release de GitHub inválida.")
            }

            val assets = releaseJson.optJSONArray("assets")
                ?: return@withContext UpdateResult.Failure(
                    "La Release no contiene los assets requeridos."
                )

            var metadataUrl: String? = null
            val assetUrls = mutableMapOf<String, String>()
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("browser_download_url").trim()
                if (name.isBlank() || !isAllowedAssetUrl(url)) continue
                assetUrls[name] = url
                if (name == UPDATE_METADATA_ASSET) metadataUrl = url
            }

            val safeMetadataUrl = metadataUrl
                ?: return@withContext UpdateResult.Failure(
                    "La Release no contiene update.json."
                )
            val metadataResponse = getJson(safeMetadataUrl)
            if (metadataResponse.status !in 200..299) {
                return@withContext UpdateResult.Failure(
                    "No se pudo descargar update.json.",
                    httpStatus = metadataResponse.status,
                    retryable = true
                )
            }

            val metadata = JSONObject(metadataResponse.body)
            val versionCode = metadata.optLong("versionCode", -1L)
            val versionName = metadata.optString("versionName").trim().take(80)
            val apkAsset = metadata.optString("apkAsset").trim()
            val sha256 = metadata.optString("sha256").trim().lowercase()
            val mandatory = metadata.optBoolean("mandatory", true)
            val minVersionCode = metadata.optLong("minVersionCode", versionCode)
            val apkUrl = assetUrls[apkAsset]

            if (
                versionCode <= 0L ||
                versionName.isBlank() ||
                apkAsset.isBlank() ||
                !SHA256_REGEX.matches(sha256) ||
                minVersionCode <= 0L ||
                apkUrl == null
            ) {
                return@withContext UpdateResult.Failure(
                    "Los metadatos de actualización no son válidos."
                )
            }

            val publishedAt = releaseJson.optString("published_at")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH
            val notes = releaseJson.optString("body", "")
                .trim()
                .take(MAX_RELEASE_NOTES_LENGTH)
                .takeIf { it.isNotBlank() }

            val release = AppRelease(
                releaseId = releaseId,
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                apkAssetName = apkAsset,
                sha256 = sha256,
                mandatory = mandatory,
                minVersionCode = minVersionCode,
                releaseNotes = notes,
                publishedAt = publishedAt
            )

            cache.saveCheck(release)
            if (BuildConfig.VERSION_CODE.toLong() < release.versionCode) {
                UpdateResult.Available(release)
            } else {
                UpdateResult.UpToDate(release)
            }
        } catch (_: SocketTimeoutException) {
            UpdateResult.Failure("La comprobación de GitHub agotó el tiempo de espera.")
        } catch (_: IOException) {
            UpdateResult.Failure("No se pudo conectar con GitHub.")
        } catch (_: Throwable) {
            UpdateResult.Failure("No se pudo comprobar la última versión.")
        }
    }

    override suspend fun downloadUpdate(release: AppRelease): Flow<DownloadState> =
        downloader.download(release)

    private fun getJson(rawUrl: String): HttpResponse {
        val url = URL(rawUrl)
        require(url.protocol.equals("https", ignoreCase = true)) { "HTTPS requerido" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DensityReset/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun failureForStatus(status: Int): UpdateResult.Failure = when (status) {
        403 -> UpdateResult.Failure(
            "GitHub rechazó temporalmente la comprobación o se alcanzó el límite de API.",
            httpStatus = status
        )
        404 -> UpdateResult.Failure(
            "No se encontró una Release estable publicada.",
            httpStatus = status
        )
        429 -> UpdateResult.Failure(
            "GitHub limitó temporalmente las comprobaciones.",
            httpStatus = status
        )
        in 500..599 -> UpdateResult.Failure(
            "GitHub no está disponible temporalmente.",
            httpStatus = status
        )
        else -> UpdateResult.Failure(
            "GitHub respondió HTTP $status.",
            httpStatus = status
        )
    }

    private fun isAllowedAssetUrl(rawUrl: String): Boolean = runCatching {
        val url = URL(rawUrl)
        url.protocol.equals("https", true) &&
            url.host.equals("github.com", true)
    }.getOrDefault(false)

    private data class HttpResponse(val status: Int, val body: String)

    private companion object {
        const val UPDATE_METADATA_ASSET = "update.json"
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val MAX_RELEASE_NOTES_LENGTH = 8_000
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

private class UpdateCacheStorage(private val context: Context) {
    suspend fun saveCheck(release: AppRelease) {
        context.updateCacheDataStore.edit { preferences ->
            preferences[Keys.lastUpdateCheck] = System.currentTimeMillis()
            preferences[Keys.latestVersionCode] = release.versionCode
            preferences[Keys.latestVersionName] = release.versionName
            preferences[Keys.releaseId] = release.releaseId
            preferences[Keys.releasePublishedAt] = release.publishedAt.toString()
        }
    }

    private object Keys {
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val latestVersionCode = longPreferencesKey("latest_version_code")
        val latestVersionName = stringPreferencesKey("latest_version_name")
        val releaseId = longPreferencesKey("release_id")
        val releasePublishedAt = stringPreferencesKey("release_published_at")
    }
}

private val Context.updateCacheDataStore by preferencesDataStore(name = "update_cache")
