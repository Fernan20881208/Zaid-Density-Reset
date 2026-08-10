package com.zaid.densityreset.startup

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.zaid.densityreset.license.ui.LicenseGateActivity

object StartupActivityGuard : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var registered = false

    fun register(application: Application) {
        if (registered) return
        application.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is StartupActivity) return

        val gate = StartupCoordinator.currentGate()
        if (activity is LicenseGateActivity && gate is StartupGate.LicenseRequired) return
        if (gate is StartupGate.Ready) return

        activity.startActivity(
            Intent(activity, StartupActivity::class.java).apply {
                action = when (activity::class.java.simpleName) {
                    "MainActivity" -> StartupActivity.ACTION_OPEN_LEGACY_CONTROLS
                    else -> StartupActivity.ACTION_OPEN_GAME_LAUNCHER
                }
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        activity.finish()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
