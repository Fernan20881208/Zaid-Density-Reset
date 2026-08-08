package com.zaid.densityreset.license.ui

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.zaid.densityreset.R
import com.zaid.densityreset.databinding.ViewLicensePanelBinding
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.domain.LicenseState
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LicensePanelBinder(
    private val activity: AppCompatActivity
) {
    private var binding: ViewLicensePanelBinding? = null

    fun attach() {
        if (binding != null) return
        val contactButton = activity.findViewById<View>(R.id.buttonInstagram) ?: return
        val contactCard = contactButton.parent as? View ?: return
        val container = contactCard.parent as? ViewGroup ?: return

        val panel = ViewLicensePanelBinding.inflate(activity.layoutInflater, container, false)
        val contactIndex = container.indexOfChild(contactCard).coerceAtLeast(0)
        container.addView(panel.root, contactIndex)
        binding = panel

        panel.buttonVerifyLicense.setOnClickListener {
            panel.buttonVerifyLicense.isEnabled = false
            activity.lifecycleScope.launch {
                val result = LicenseManager.validateNow()
                panel.buttonVerifyLicense.isEnabled = true
                val message = result.message ?: if (result.success) {
                    activity.getString(R.string.license_verified_successfully)
                } else {
                    activity.getString(R.string.license_verification_failed)
                }
                Snackbar.make(panel.root, message, Snackbar.LENGTH_LONG).show()
            }
        }

        panel.buttonLicenseLogout.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.license_logout_title)
                .setMessage(R.string.license_logout_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.license_logout_button) { _, _ ->
                    activity.lifecycleScope.launch {
                        LicenseManager.logout()
                    }
                }
                .show()
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LicenseManager.state.collect(::render)
            }
        }
    }

    private fun render(state: LicenseState) {
        val panel = binding ?: return
        when (state) {
            LicenseState.Checking -> {
                panel.licensePanelStatus.text = activity.getString(R.string.license_checking)
                panel.licensePanelExpires.text = activity.getString(R.string.license_expiration_unknown)
            }
            is LicenseState.Active -> {
                panel.licensePanelStatus.text = activity.getString(
                    if (state.offlineGrace) R.string.license_status_offline_grace
                    else R.string.license_status_active
                )
                panel.licensePanelExpires.text = state.expiresAt?.let {
                    activity.getString(
                        R.string.license_expires_format,
                        DISPLAY_FORMATTER.format(it.atZone(ZoneId.systemDefault()))
                    )
                } ?: activity.getString(R.string.license_permanent)
            }
            is LicenseState.Expired -> renderUnavailable(activity.getString(R.string.license_expired_message))
            LicenseState.Revoked -> renderUnavailable(activity.getString(R.string.license_revoked_message))
            LicenseState.Disabled -> renderUnavailable(activity.getString(R.string.license_disabled_message))
            LicenseState.NetworkRequired -> renderUnavailable(activity.getString(R.string.license_network_required))
            LicenseState.AppVersionBlocked -> renderUnavailable(activity.getString(R.string.license_app_version_blocked))
            LicenseState.NoLicense -> renderUnavailable(activity.getString(R.string.license_no_active_license))
            is LicenseState.Error -> renderUnavailable(state.message)
        }
        panel.licensePanelDevice.text = activity.getString(R.string.license_device_linked)
    }

    private fun renderUnavailable(message: String) {
        val panel = binding ?: return
        panel.licensePanelStatus.text = message
        panel.licensePanelExpires.text = activity.getString(R.string.license_expiration_unknown)
    }

    companion object {
        private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")
    }
}
