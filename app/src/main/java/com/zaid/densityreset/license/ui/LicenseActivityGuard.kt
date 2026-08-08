package com.zaid.densityreset.license.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.license.LicenseManager

class LicenseActivityGuard : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MainActivity) {
            if (!LicenseManager.hasConfirmedAccessForProcess()) {
                redirectToGate(activity)
                return
            }
            LicensePanelBinder(activity).attach()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity && !LicenseManager.hasConfirmedAccessForProcess()) {
            redirectToGate(activity)
        }
    }

    private fun redirectToGate(activity: Activity) {
        activity.startActivity(
            Intent(activity, LicenseGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        activity.finish()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
