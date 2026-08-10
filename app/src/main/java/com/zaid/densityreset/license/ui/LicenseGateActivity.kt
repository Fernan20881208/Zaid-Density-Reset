package com.zaid.densityreset.license.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.R
import com.zaid.densityreset.databinding.ActivityLicenseGateBinding
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.domain.LicenseState
import com.zaid.densityreset.license.util.LicenseKeyFormatter
import com.zaid.densityreset.startup.StartupActivity
import com.zaid.densityreset.startup.StartupCoordinator
import com.zaid.densityreset.util.ImageAssets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LicenseGateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLicenseGateBinding
    private var changingText = false
    private var openingMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLicenseGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureBranding()
        applyInsets()
        configureKeyInput()
        configureActions()
        observeLicenseState()

        lifecycleScope.launch {
            LicenseManager.checkOnLaunch()
        }
    }

    private fun configureBranding() {
        runCatching {
            val bytes = Base64.decode(ImageAssets.BACKGROUND_BASE64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let(binding.licenseBackgroundImage::setImageBitmap)
        binding.licenseLogo.apply {
            setBackgroundResource(R.drawable.bg_logo_clip)
            clipToOutline = true
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.licenseGateRoot) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, safe.top, 0, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.licenseGateRoot)
    }

    private fun configureKeyInput() {
        binding.licenseKeyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (changingText) return
                val formatted = LicenseKeyFormatter.formatForInput(editable?.toString().orEmpty())
                if (formatted == editable?.toString()) return
                changingText = true
                binding.licenseKeyInput.setText(formatted)
                binding.licenseKeyInput.setSelection(formatted.length)
                changingText = false
            }
        })
        binding.licenseKeyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                activateCurrentKey()
                true
            } else {
                false
            }
        }
        binding.licenseKeyInput.setOnFocusChangeListener { _, _ ->
            binding.licenseKeyInput.setBackgroundResource(R.drawable.bg_license_input)
        }
    }

    private fun configureActions() {
        binding.buttonActivateLicense.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            activateCurrentKey()
        }
    }

    private fun activateCurrentKey() {
        val key = binding.licenseKeyInput.text?.toString().orEmpty()
        if (!LicenseKeyFormatter.isValid(key)) {
            showInputError(getString(R.string.license_invalid_key))
            return
        }
        setChecking(getString(R.string.license_verifying))
        lifecycleScope.launch {
            val result = LicenseManager.activate(key)
            if (!result.success) {
                showInputError(
                    result.message ?: getString(R.string.license_verification_failed)
                )
            }
        }
    }

    private fun observeLicenseState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LicenseManager.state.collect(::renderState)
            }
        }
    }

    private fun renderState(state: LicenseState) {
        when (state) {
            LicenseState.Checking -> setChecking(getString(R.string.license_checking))
            LicenseState.NoLicense -> showActivationPrompt(null)
            is LicenseState.Active -> openMainScreen()
            is LicenseState.Expired -> showActivationPrompt(
                getString(R.string.license_expired_message)
            )
            LicenseState.Revoked -> showActivationPrompt(
                getString(R.string.license_revoked_message)
            )
            LicenseState.Disabled -> showActivationPrompt(
                getString(R.string.license_disabled_message)
            )
            LicenseState.NetworkRequired -> showActivationPrompt(
                getString(R.string.license_network_required)
            )
            LicenseState.AppVersionBlocked -> showActivationPrompt(
                getString(R.string.license_app_version_blocked)
            )
            is LicenseState.Error -> showActivationPrompt(state.message)
        }
    }

    private fun setChecking(message: String) {
        binding.licenseProgress.visibility = View.VISIBLE
        binding.buttonActivateLicense.isEnabled = false
        binding.licenseKeyInput.isEnabled = false
        binding.licenseGateStatus.text = message
        binding.licenseGateStatus.setTextColor(getColor(R.color.glass_text_secondary))
    }

    private fun showActivationPrompt(message: String?) {
        if (openingMain) return
        binding.licenseProgress.visibility = View.GONE
        binding.buttonActivateLicense.isEnabled = true
        binding.licenseKeyInput.isEnabled = true
        binding.licenseGateStatus.text = message ?: getString(R.string.license_enter_key_prompt)
        binding.licenseGateStatus.setTextColor(
            getColor(if (message == null) R.color.glass_text_secondary else R.color.status_warning)
        )
    }

    private fun showInputError(message: String) {
        binding.licenseProgress.visibility = View.GONE
        binding.buttonActivateLicense.isEnabled = true
        binding.licenseKeyInput.isEnabled = true
        binding.licenseKeyInput.setBackgroundResource(R.drawable.bg_license_input_error)
        binding.licenseGateStatus.text = message
        binding.licenseGateStatus.setTextColor(getColor(R.color.status_error))
        binding.licenseKeyInput.performHapticFeedback(HapticFeedbackConstants.REJECT)
        ObjectAnimator.ofFloat(
            binding.licenseKeyInput,
            View.TRANSLATION_X,
            0f,
            -12f,
            12f,
            -8f,
            8f,
            0f
        ).apply {
            duration = 280L
            start()
        }
    }

    private fun openMainScreen() {
        if (openingMain) return
        openingMain = true
        binding.licenseProgress.visibility = View.GONE
        binding.licenseGateStatus.text = getString(R.string.license_valid)
        binding.licenseGateStatus.setTextColor(getColor(R.color.status_success))
        binding.licenseKeyInput.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lifecycleScope.launch {
            delay(450L)
            StartupCoordinator.resetForRecheck()
            startActivity(
                Intent(this@LicenseGateActivity, StartupActivity::class.java).apply {
                    action = StartupActivity.ACTION_OPEN_GAME_LAUNCHER
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
