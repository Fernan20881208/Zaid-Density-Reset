package com.zaid.densityreset.license

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zaid.densityreset.license.data.LicenseRepositoryImpl
import com.zaid.densityreset.license.domain.LicenseRepository
import com.zaid.densityreset.license.domain.LicenseResult
import com.zaid.densityreset.license.domain.LicenseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object LicenseManager : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: LicenseRepository
    private var periodicJob: Job? = null
    private var lastValidationAttemptElapsed = 0L

    @Volatile
    private var initialized = false

    @Volatile
    private var processAccessConfirmed = false

    fun initialize(application: Application) {
        if (initialized) return
        repository = LicenseRepositoryImpl(application.applicationContext)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        initialized = true
    }

    val state: Flow<LicenseState>
        get() {
            check(initialized) { "LicenseManager is not initialized." }
            return repository.observeLicenseState()
        }

    fun hasConfirmedAccessForProcess(): Boolean = processAccessConfirmed

    suspend fun checkOnLaunch(): LicenseResult {
        processAccessConfirmed = false
        return validateInternal()
    }

    suspend fun activate(key: String): LicenseResult {
        val result = repository.activate(key)
        processAccessConfirmed = result.success
        if (result.success) lastValidationAttemptElapsed = SystemClock.elapsedRealtime()
        return result
    }

    suspend fun validateNow(): LicenseResult = validateInternal()

    suspend fun logout() {
        processAccessConfirmed = false
        periodicJob?.cancel()
        repository.logout()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!initialized || !processAccessConfirmed) return
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastValidationAttemptElapsed >= FOREGROUND_REVALIDATION_MILLIS) {
            scope.launch { validateInternal() }
        }
        startPeriodicValidation()
    }

    override fun onStop(owner: LifecycleOwner) {
        periodicJob?.cancel()
        periodicJob = null
    }

    private suspend fun validateInternal(): LicenseResult {
        lastValidationAttemptElapsed = SystemClock.elapsedRealtime()
        val result = repository.validate()
        processAccessConfirmed = result.success
        if (!result.success) {
            periodicJob?.cancel()
            periodicJob = null
        }
        return result
    }

    private fun startPeriodicValidation() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (processAccessConfirmed) {
                delay(PERIODIC_VALIDATION_MILLIS)
                if (!processAccessConfirmed) break
                validateInternal()
            }
        }
    }

    private const val FOREGROUND_REVALIDATION_MILLIS = 15L * 60L * 1_000L
    private const val PERIODIC_VALIDATION_MILLIS = 20L * 60L * 1_000L
}
