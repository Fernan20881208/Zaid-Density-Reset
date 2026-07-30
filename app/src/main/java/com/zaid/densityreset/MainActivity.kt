package com.zaid.densityreset

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.zaid.densityreset.accessibility.VolumeShortcutAccessibilityService
import com.zaid.densityreset.databinding.ActivityMainBinding
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.util.AccessibilityUtils
import com.zaid.densityreset.util.AppPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val stateListener: (ShizukuManager.State) -> Unit = { state ->
        renderShizukuState(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        configurePreferences()
        configureActions()
        ShizukuManager.addStateListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        renderAccessibilityState()
        ShizukuManager.refresh()
    }

    override fun onDestroy() {
        ShizukuManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    private fun configurePreferences() {
        binding.switchBlockVolume.isChecked =
            AppPreferences.shouldBlockVolumeChanges(this)
        binding.switchVibration.isChecked =
            AppPreferences.shouldVibrateAfterSuccess(this)

        binding.switchBlockVolume.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setBlockVolumeChanges(this, checked)
        }
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setVibrateAfterSuccess(this, checked)
        }
    }

    private fun configureActions() {
        binding.buttonRequestPermission.setOnClickListener {
            showMessage(ShizukuManager.requestPermission())
        }

        binding.buttonOpenShizuku.setOnClickListener {
            if (!ShizukuManager.openShizuku()) {
                showMessage(getString(R.string.cannot_open_shizuku))
            }
        }

        binding.buttonAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.buttonTest.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                showMessage(getString(R.string.test_accessibility_notice))
            }
            executeTest()
        }
    }

    private fun executeTest() {
        setTestRunning(true)
        binding.testResult.text = getString(R.string.testing_density_reset)
        binding.testResult.setTextColor(color(R.color.status_warning))

        ShizukuManager.resetDensity { result ->
            setTestRunning(false)
            val detail = buildString {
                append(result.message)
                if (result.stderr.isNotBlank()) {
                    append('\n')
                    append(result.stderr)
                } else if (result.stdout.isNotBlank()) {
                    append('\n')
                    append(result.stdout)
                }
            }
            binding.testResult.text = detail
            binding.testResult.setTextColor(
                color(if (result.success) R.color.status_success else R.color.status_error)
            )
            showMessage(result.message)
        }
    }

    private fun setTestRunning(running: Boolean) {
        binding.buttonTest.isEnabled = !running
        binding.buttonTest.text = getString(
            if (running) R.string.testing_density_reset else R.string.test_density_reset
        )
        binding.testProgress.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun renderShizukuState(state: ShizukuManager.State) {
        setStatus(
            binding.statusInstallation,
            getString(R.string.label_installation),
            getString(
                if (state.installed) R.string.status_installed
                else R.string.status_not_installed
            ),
            if (state.installed) R.color.status_success else R.color.status_error
        )

        setStatus(
            binding.statusShizukuService,
            getString(R.string.label_shizuku_service),
            getString(
                if (state.running) R.string.status_started
                else R.string.status_stopped
            ),
            if (state.running) R.color.status_success else R.color.status_warning
        )

        setStatus(
            binding.statusPermission,
            getString(R.string.label_permission),
            getString(
                if (state.permissionGranted) R.string.status_permission_granted
                else R.string.status_permission_denied
            ),
            if (state.permissionGranted) R.color.status_success else R.color.status_warning
        )

        setStatus(
            binding.statusUserService,
            getString(R.string.label_user_service),
            getString(
                if (state.userServiceConnected) R.string.status_user_service_connected
                else R.string.status_user_service_disconnected
            ),
            if (state.userServiceConnected) R.color.status_success else R.color.status_warning
        )

        binding.buttonRequestPermission.isEnabled = state.running && !state.permissionGranted
        binding.buttonOpenShizuku.isEnabled = state.installed
    }

    private fun renderAccessibilityState() {
        val enabled = isAccessibilityEnabled()
        setStatus(
            binding.statusAccessibility,
            getString(R.string.label_accessibility),
            getString(
                if (enabled) R.string.status_accessibility_enabled
                else R.string.status_accessibility_disabled
            ),
            if (enabled) R.color.status_success else R.color.status_error
        )
    }

    private fun isAccessibilityEnabled(): Boolean =
        AccessibilityUtils.isServiceEnabled(
            this,
            VolumeShortcutAccessibilityService::class.java
        )

    private fun setStatus(
        view: android.widget.TextView,
        label: String,
        value: String,
        @ColorRes colorRes: Int
    ) {
        view.text = getString(R.string.status_line_format, label, value)
        view.setTextColor(color(colorRes))
    }

    private fun color(@ColorRes colorRes: Int): Int =
        ContextCompat.getColor(this, colorRes)

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
