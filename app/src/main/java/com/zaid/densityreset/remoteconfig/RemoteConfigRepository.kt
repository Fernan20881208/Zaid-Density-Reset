package com.zaid.densityreset.remoteconfig

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface RemoteConfigRepository {
    suspend fun refresh(): Result<RemoteAppConfig>
    fun observeConfig(): Flow<RemoteAppConfig>
    suspend fun cachedSnapshot(): RemoteConfigCache
}

data class RemoteConfigCache(
    val config: RemoteAppConfig,
    val hasValidCache: Boolean,
    val lastSuccessAt: Long?
)

class RemoteConfigRepositoryImpl(context: Context) : RemoteConfigRepository {

    private val appContext = context.applicationContext
    private val storage = RemoteConfigStorage(appContext)

    override suspend fun refresh(): Result<RemoteAppConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.LICENSE_API_URL
                .replace(Regex("/license-api/?$"), "/app-config")
            require(endpoint.startsWith("https://")) { "Remote Config requiere HTTPS." }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DensityReset/${BuildConfig.VERSION_NAME}")
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Remote Config respondió HTTP $status")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                if (!root.optBoolean("success", false)) {
                    throw IllegalStateException("Remote Config inválido")
                }
                val parsed = parseConfig(root.getJSONObject("config")).validated()
                storage.save(parsed)
                parsed
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun observeConfig(): Flow<RemoteAppConfig> =
        storage.observe().map { it.config }

    override suspend fun cachedSnapshot(): RemoteConfigCache =
        storage.observe().first()

    private fun parseConfig(json: JSONObject): RemoteAppConfig {
        val blocked = buildSet {
            val values = json.optJSONArray("blocked_version_codes")
            if (values != null) {
                for (index in 0 until values.length()) {
                    values.optLong(index, -1L)
                        .takeIf { it > 0L }
                        ?.let(::add)
                }
            }
        }
        val latest = if (json.isNull("latest_version_code")) {
            null
        } else {
            json.optLong("latest_version_code", -1L).takeIf { it > 0L }
        }
        return RemoteAppConfig(
            maintenanceMode = json.optBoolean("maintenance_mode", false),
            maintenanceMessage = json.optNullableString("maintenance_message"),
            minSupportedVersionCode = json.optLong("min_supported_version_code", 1L),
            latestVersionCode = latest,
            forceUpdate = json.optBoolean("force_update", false),
            freeFireEnabled = json.optBoolean("free_fire_enabled", true),
            freeFireMaxEnabled = json.optBoolean("free_fire_max_enabled", true),
            ultraEnabled = json.optBoolean("sensi_ultra_enabled", true),
            highEnabled = json.optBoolean("sensi_high_enabled", true),
            lowEnabled = json.optBoolean("sensi_low_enabled", true),
            ultraDensity = json.optInt("sensi_ultra_density", 20),
            highDensity = json.optInt("sensi_high_density", 72),
            lowDensity = json.optInt("sensi_low_density", 280),
            gameSessionDurationSeconds = json.optInt("game_session_duration_seconds", 30),
            announcementEnabled = json.optBoolean("announcement_enabled", false),
            announcementTitle = json.optNullableString("announcement_title"),
            announcementMessage = json.optNullableString("announcement_message"),
            quickTileEnabled = json.optBoolean("quick_tile_enabled", true),
            githubUpdatesEnabled = json.optBoolean("github_updates_enabled", true),
            blockedVersionCodes = blocked
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 8_000
    }
}

private class RemoteConfigStorage(private val context: Context) {

    fun observe(): Flow<RemoteConfigCache> = context.remoteConfigDataStore.data.map(::decode)

    suspend fun save(config: RemoteAppConfig) {
        val safe = config.validated()
        context.remoteConfigDataStore.edit { preferences ->
            preferences[Keys.hasCache] = true
            preferences[Keys.lastSuccessAt] = System.currentTimeMillis()
            preferences[Keys.maintenanceMode] = safe.maintenanceMode
            putNullable(preferences, Keys.maintenanceMessage, safe.maintenanceMessage)
            preferences[Keys.minSupportedVersionCode] = safe.minSupportedVersionCode
            safe.latestVersionCode?.let {
                preferences[Keys.latestVersionCode] = it
            } ?: preferences.remove(Keys.latestVersionCode)
            preferences[Keys.forceUpdate] = safe.forceUpdate
            preferences[Keys.freeFireEnabled] = safe.freeFireEnabled
            preferences[Keys.freeFireMaxEnabled] = safe.freeFireMaxEnabled
            preferences[Keys.ultraEnabled] = safe.ultraEnabled
            preferences[Keys.highEnabled] = safe.highEnabled
            preferences[Keys.lowEnabled] = safe.lowEnabled
            preferences[Keys.ultraDensity] = safe.ultraDensity
            preferences[Keys.highDensity] = safe.highDensity
            preferences[Keys.lowDensity] = safe.lowDensity
            preferences[Keys.sessionDurationSeconds] = safe.gameSessionDurationSeconds
            preferences[Keys.announcementEnabled] = safe.announcementEnabled
            putNullable(preferences, Keys.announcementTitle, safe.announcementTitle)
            putNullable(preferences, Keys.announcementMessage, safe.announcementMessage)
            preferences[Keys.quickTileEnabled] = safe.quickTileEnabled
            preferences[Keys.githubUpdatesEnabled] = safe.githubUpdatesEnabled
            preferences[Keys.blockedVersionCodes] = safe.blockedVersionCodes
                .sorted()
                .joinToString(",")
        }
    }

    private fun decode(preferences: Preferences): RemoteConfigCache {
        val fallback = RemoteAppConfig.DEFAULT
        val blocked = preferences[Keys.blockedVersionCodes]
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .toSet()
        val config = RemoteAppConfig(
            maintenanceMode = preferences[Keys.maintenanceMode] ?: fallback.maintenanceMode,
            maintenanceMessage = preferences[Keys.maintenanceMessage],
            minSupportedVersionCode = preferences[Keys.minSupportedVersionCode]
                ?: fallback.minSupportedVersionCode,
            latestVersionCode = preferences[Keys.latestVersionCode],
            forceUpdate = preferences[Keys.forceUpdate] ?: fallback.forceUpdate,
            freeFireEnabled = preferences[Keys.freeFireEnabled] ?: fallback.freeFireEnabled,
            freeFireMaxEnabled = preferences[Keys.freeFireMaxEnabled] ?: fallback.freeFireMaxEnabled,
            ultraEnabled = preferences[Keys.ultraEnabled] ?: fallback.ultraEnabled,
            highEnabled = preferences[Keys.highEnabled] ?: fallback.highEnabled,
            lowEnabled = preferences[Keys.lowEnabled] ?: fallback.lowEnabled,
            ultraDensity = preferences[Keys.ultraDensity] ?: fallback.ultraDensity,
            highDensity = preferences[Keys.highDensity] ?: fallback.highDensity,
            lowDensity = preferences[Keys.lowDensity] ?: fallback.lowDensity,
            gameSessionDurationSeconds = preferences[Keys.sessionDurationSeconds]
                ?: fallback.gameSessionDurationSeconds,
            announcementEnabled = preferences[Keys.announcementEnabled]
                ?: fallback.announcementEnabled,
            announcementTitle = preferences[Keys.announcementTitle],
            announcementMessage = preferences[Keys.announcementMessage],
            quickTileEnabled = preferences[Keys.quickTileEnabled] ?: fallback.quickTileEnabled,
            githubUpdatesEnabled = preferences[Keys.githubUpdatesEnabled]
                ?: fallback.githubUpdatesEnabled,
            blockedVersionCodes = blocked
        ).validated()
        return RemoteConfigCache(
            config = config,
            hasValidCache = preferences[Keys.hasCache] ?: false,
            lastSuccessAt = preferences[Keys.lastSuccessAt]
        )
    }

    private fun putNullable(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value == null) preferences.remove(key) else preferences[key] = value
    }

    private object Keys {
        val hasCache = booleanPreferencesKey("has_cache")
        val lastSuccessAt = longPreferencesKey("last_success_at")
        val maintenanceMode = booleanPreferencesKey("maintenance_mode")
        val maintenanceMessage = stringPreferencesKey("maintenance_message")
        val minSupportedVersionCode = longPreferencesKey("min_supported_version_code")
        val latestVersionCode = longPreferencesKey("latest_version_code")
        val forceUpdate = booleanPreferencesKey("force_update")
        val freeFireEnabled = booleanPreferencesKey("free_fire_enabled")
        val freeFireMaxEnabled = booleanPreferencesKey("free_fire_max_enabled")
        val ultraEnabled = booleanPreferencesKey("sensi_ultra_enabled")
        val highEnabled = booleanPreferencesKey("sensi_high_enabled")
        val lowEnabled = booleanPreferencesKey("sensi_low_enabled")
        val ultraDensity = intPreferencesKey("sensi_ultra_density")
        val highDensity = intPreferencesKey("sensi_high_density")
        val lowDensity = intPreferencesKey("sensi_low_density")
        val sessionDurationSeconds = intPreferencesKey("game_session_duration_seconds")
        val announcementEnabled = booleanPreferencesKey("announcement_enabled")
        val announcementTitle = stringPreferencesKey("announcement_title")
        val announcementMessage = stringPreferencesKey("announcement_message")
        val quickTileEnabled = booleanPreferencesKey("quick_tile_enabled")
        val githubUpdatesEnabled = booleanPreferencesKey("github_updates_enabled")
        val blockedVersionCodes = stringPreferencesKey("blocked_version_codes")
    }
}

private val Context.remoteConfigDataStore by preferencesDataStore(
    name = "remote_app_config"
)

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name, null)?.trim()?.takeIf { it.isNotEmpty() }
