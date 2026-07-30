package com.zaid.densityreset

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.accessibility.VolumeShortcutAccessibilityService
import com.zaid.densityreset.databinding.ActivityMainBinding
import com.zaid.densityreset.databinding.DialogUltraConfirmationBinding
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.density.DensityUiState
import com.zaid.densityreset.density.DensityViewModel
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.util.AccessibilityUtils
import com.zaid.densityreset.util.AppPreferences
import com.zaid.densityreset.util.ImageAssets
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val densityViewModel: DensityViewModel by viewModels()

    private val stateListener: (ShizukuManager.State) -> Unit = { state ->
        renderShizukuState(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureBranding()
        applySystemBarInsets()

        configurePreferences()
        configureActions()
        observeDensityState()
        ShizukuManager.addStateListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        renderAccessibilityState()
        ShizukuManager.refresh()
        densityViewModel.refresh()
    }

    override fun onDestroy() {
        ShizukuManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    private fun configureBranding() {
        decodeImage(ImageAssets.BACKGROUND_BASE64)?.let { bitmap ->
            binding.backgroundImage.setImageBitmap(bitmap)
        }
    }

    private fun decodeImage(encoded: String): Bitmap? = runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
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

        binding.buttonReconnectUserService.setOnClickListener {
            showMessage(ShizukuManager.reconnectUserService())
            densityViewModel.refresh()
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

        binding.buttonInstagram.setOnClickListener {
            openInstagramProfile()
        }

        binding.presetUltra.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showUltraConfirmation()
        }
        binding.presetHigh.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            densityViewModel.applyPreset(DensityPreset.HIGH)
        }
        binding.presetLow.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            densityViewModel.applyPreset(DensityPreset.LOW)
        }
        binding.buttonEmergencyReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            densityViewModel.resetDensity()
        }
    }

    private fun observeDensityState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                densityViewModel.uiState.collect(::renderDensityState)
            }
        }
    }

    private fun renderDensityState(state: DensityUiState) {
        binding.densityStatus.text = state.statusLabel
        binding.densityCurrentValue.text = state.currentDensity?.let {
            getString(R.string.current_density_value, it)
        } ?: getString(R.string.current_density_unknown)

        val selected = state.activePreset
        binding.presetUltra.isSelected = selected == DensityPreset.ULTRA
        binding.presetHigh.isSelected = selected == DensityPreset.HIGH
        binding.presetLow.isSelected = selected == DensityPreset.LOW

        binding.presetUltraState.text = presetStateText(selected == DensityPreset.ULTRA)
        binding.presetHighState.text = presetStateText(selected == DensityPreset.HIGH)
        binding.presetLowState.text = presetStateText(selected == DensityPreset.LOW)

        binding.densityProgressContainer.visibility =
            if (state.isApplying || state.isRefreshing) View.VISIBLE else View.GONE
        binding.densityProgressText.text = if (state.isApplying) {
            getString(R.string.applying_configuration)
        } else {
            getString(R.string.reading_density)
        }

        binding.densityOperationMessage.text = state.operationMessage
        binding.densityOperationMessage.setTextColor(
            color(
                when {
                    state.operationMessage == getString(R.string.dpi_applied_successfully) ||
                        state.operationMessage == getString(R.string.dpi_reset_successfully) ->
                        R.color.status_success
                    state.operationMessage.startsWith("No ") ||
                        state.operationMessage.startsWith("El ") ||
                        state.operationMessage.startsWith("Shizuku") ||
                        state.operationMessage.startsWith("Permiso") ->
                        R.color.status_error
                    else -> R.color.glass_text_secondary
                }
            )
        )

        binding.densityLastChange.text = state.lastChangedAt?.let { timestamp ->
            getString(
                R.string.last_density_change,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(timestamp))
            )
        } ?: getString(R.string.no_density_changes)

        val enabled = !state.isApplying && !state.isRefreshing
        binding.presetUltra.isEnabled = enabled
        binding.presetHigh.isEnabled = enabled
        binding.presetLow.isEnabled = enabled
        binding.buttonEmergencyReset.isEnabled = !state.isApplying
    }

    private fun presetStateText(active: Boolean): String =
        getString(if (active) R.string.preset_active else R.string.preset_inactive)

    private fun showUltraConfirmation() {
        val dialog = Dialog(this)
        val dialogBinding = DialogUltraConfirmationBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val handler = Handler(Looper.getMainLooper())
        var confirmed = false
        var progressAnimator: ObjectAnimator? = null

        val confirmRunnable = Runnable {
            confirmed = true
            dialogBinding.buttonApplyUltra.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS
            )
            dialog.dismiss()
            densityViewModel.applyPreset(DensityPreset.ULTRA)
        }

        fun resetHoldState() {
            handler.removeCallbacks(confirmRunnable)
            progressAnimator?.cancel()
            dialogBinding.ultraHoldProgress.progress = 0
            dialogBinding.buttonApplyUltra.text =
                getString(R.string.hold_to_apply_ultra)
            dialogBinding.buttonApplyUltra.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start()
        }

        dialogBinding.buttonApplyUltra.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    confirmed = false
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    dialogBinding.buttonApplyUltra.text =
                        getString(R.string.keep_holding)
                    dialogBinding.buttonApplyUltra.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(120L)
                        .start()
                    progressAnimator = ObjectAnimator.ofInt(
                        dialogBinding.ultraHoldProgress,
                        "progress",
                        0,
                        100
                    ).apply {
                        duration = ULTRA_CONFIRM_HOLD_MILLIS
                        start()
                    }
                    handler.postDelayed(confirmRunnable, ULTRA_CONFIRM_HOLD_MILLIS)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    if (!confirmed) resetHoldState()
                    true
                }

                else -> true
            }
        }

        dialogBinding.buttonApplyUltra.setOnClickListener {
            it.performClick()
        }
        dialogBinding.buttonCancelUltra.setOnClickListener {
            resetHoldState()
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            resetHoldState()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun openInstagramProfile() {
        val username = getString(R.string.instagram_username)
        val instagramIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("instagram://user?username=$username")
        ).apply {
            setPackage("com.instagram.android")
        }

        val openedInApp = runCatching {
            startActivity(instagramIntent)
            true
        }.getOrDefault(false)

        if (!openedInApp) {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.instagram.com/$username/")
            )
            runCatching { startActivity(browserIntent) }
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
            if (result.success) densityViewModel.recordExternalReset()
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

        val userServiceText = when {
            state.userServiceConnected -> R.string.status_user_service_connected
            state.bindingInProgress -> R.string.status_user_service_connecting
            else -> R.string.status_user_service_disconnected
        }
        val userServiceColor = when {
            state.userServiceConnected -> R.color.status_success
            state.bindingInProgress -> R.color.status_warning
            else -> R.color.status_error
        }
        setStatus(
            binding.statusUserService,
            getString(R.string.label_user_service),
            getString(userServiceText),
            userServiceColor
        )

        binding.shizukuDiagnostics.text = ShizukuManager.buildDiagnosticText(state)
        binding.buttonRequestPermission.isEnabled = state.running && !state.permissionGranted
        binding.buttonOpenShizuku.isEnabled = state.installed
        binding.buttonReconnectUserService.isEnabled =
            state.running && state.permissionGranted && !state.bindingInProgress
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val ULTRA_CONFIRM_HOLD_MILLIS = 1_500L
    }
}
