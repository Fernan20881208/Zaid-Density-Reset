package com.zaid.densityreset.quicktile

import android.app.Application
import com.zaid.densityreset.density.DensityPreferencesRepository
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DensityTileStateObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        val context = application.applicationContext
        val densityRepository = DensityPreferencesRepository(context)
        val sessionRepository = GameSessionRepositoryImpl(context)

        scope.launch {
            densityRepository.observe().collect {
                DensityTileNotifier.requestRefresh(context)
            }
        }
        scope.launch {
            sessionRepository.state.collect {
                DensityTileNotifier.requestRefresh(context)
            }
        }
    }
}
