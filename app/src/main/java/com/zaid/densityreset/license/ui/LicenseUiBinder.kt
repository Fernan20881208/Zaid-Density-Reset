package com.zaid.densityreset.license.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.R
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.domain.LicenseState
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object LicenseUiBinder {
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is MainActivity) attach(activity)
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private fun attach(activity: MainActivity) {
        val testButton = activity.findViewById<View>(R.id.buttonTest) ?: return
        val testCard = testButton.parent as? View ?: return
        val container = testCard.parent as? ViewGroup ?: return
        if (container.findViewById<View>(R.id.licensePanelRoot) != null) return

        val panel = LayoutInflater.from(activity)
            .inflate(R.layout.view_license_panel, container, false)
        val index = container.indexOfChild(testCard)
        container.addView(panel, index.coerceAtLeast(0))

        val status = panel.findViewById<TextView>(R.id.licensePanelStatus)
        val expires = panel.findViewById<TextView>(R.id.licensePanelExpires)
        val verify = panel.findViewById<AppCompatButton>(R.id.buttonVerifyLicense)
        val logout = panel.findViewById<AppCompatButton>(R.id.buttonLicenseLogout)
        var redirecting = false

        verify.setOnClickListener {
            verify.isEnabled = false
            activity.lifecycleScope.launch {
                LicenseManager.validateNow()
                verify.isEnabled = true
            }
        }

        logout.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.license_logout_title)
                .setMessage(R.string.license_logout_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.license_logout_button) { _, _ ->
                    activity.lifecycleScope.launch {
                        LicenseManager.logout()
                        redirectToGate(activity)
                    }
                }
                .show()
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LicenseManager.state.collect { state ->
                    when (state) {
                        LicenseState.Checking -> {
                            status.text = activity.getString(R.string.license_checking)
                            status.setTextColor(
                                ContextCompat.getColor(activity, R.color.status_warning)
                            )
                        }
                        is LicenseState.Active -> {
                            status.text = activity.getString(
                                if (state.offlineGrace) {
                                    R.string.license_status_offline_grace
                                } else {
                                    R.string.license_status_active
                                }
                            )
                            status.setTextColor(
                                ContextCompat.getColor(activity, R.color.status_success)
                            )
                            expires.text = state.expiresAt?.let { instant ->
                                activity.getString(
                                    R.string.license_expiration_value,
                                    DateTimeFormatter
                                        .ofLocalizedDateTime(
                                            FormatStyle.MEDIUM,
                                            FormatStyle.SHORT
                                        )
                                        .withZone(ZoneId.systemDefault())
                                        .format(instant)
                                )
                            } ?: activity.getString(R.string.license_permanent)
                        }
                        else -> {
                            if (!redirecting) {
                                redirecting = true
                                redirectToGate(activity)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun redirectToGate(activity: MainActivity) {
        if (activity.isFinishing) return
        activity.startActivity(
            Intent(activity, LicenseGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        activity.finish()
    }
}
