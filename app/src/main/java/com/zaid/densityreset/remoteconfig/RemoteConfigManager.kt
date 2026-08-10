package com.zaid.densityreset.remoteconfig

import android.app.Application
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object RemoteConfigManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _config = MutableStateFlow(RemoteAppConfig.DEFAULT)
    val config: StateFlow<RemoteAppConfig> = _config.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var hasValidCache = false

    @Volatile
    private var lastSuccessAt: Long? = null

    private lateinit var repository: RemoteConfigRepository
    private lateinit var application: Application

    fun initialize(app: Application) {
        if (initialized) return
        application = app
        repository = RemoteConfigRepositoryImpl(app.applicationContext)
        initialized = true
        scope.launch {
            val cached = repository.cachedSnapshot()
            hasValidCache = cached.hasValidCache
            lastSuccessAt = cached.lastSuccessAt
            _config.value = cached.config
            repository.observeConfig().collect { value ->
                _config.value = value
            }
        }
    }

    suspend fun refresh(): Result<RemoteAppConfig> {
        check(initialized) { "RemoteConfigManager is not initialized." }
        val result = repository.refresh()
        result.onSuccess { value ->
            _config.value = value
            hasValidCache = true
            lastSuccessAt = System.currentTimeMillis()
            requestTileRefresh()
        }
        return result
    }

    fun currentConfig(): RemoteAppConfig = _config.value

    fun hasCachedConfig(): Boolean = hasValidCache

    fun lastSuccessfulRefreshAt(): Long? = lastSuccessAt

    private fun requestTileRefresh() {
        runCatching {
            TileService.requestListeningState(
                application,
                android.content.ComponentName(
                    application,
                    "com.zaid.densityreset.quicktile.DensityQuickTileService"
                )
            )
        }
    }
}
