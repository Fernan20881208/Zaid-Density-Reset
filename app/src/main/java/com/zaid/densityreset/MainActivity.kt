package com.zaid.densityreset

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.snackbar.Snackbar
import com.zaid.densityreset.accessibility.VolumeShortcutAccessibilityService
import com.zaid.densityreset.databinding.ActivityMainBinding
import com.zaid.densityreset.databinding.DialogUltraConfirmationBinding
import com.zaid.densityreset.databinding.ViewDensityPanelBinding
import com.zaid.densityreset.databinding.ViewGameProfilePanelBinding
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.density.DensityUiState
import com.zaid.densityreset.density.DensityViewModel
import com.zaid.densityreset.gameprofile.domain.GameProfileUiState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.ui.GameProfileViewModel
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.util.AccessibilityUtils
import com.zaid.densityreset.util.AppPreferences
import com.zaid.densityreset.util.ImageAssets
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var densityBinding: ViewDensityPanelBinding
    private lateinit var gameProfileBinding: ViewGameProfilePanelBinding

    private val densityViewModel: DensityViewModel by viewModels()
    private val gameProfileViewModel: GameProfileViewModel by viewModels()

    private var latestDensityState = DensityUiState()
    private var latestGameProfileState = GameProfileUiState()
    private var pendingGameSessionStart = false
    private val gameIconCache = mutableMapOf<SupportedGame, Drawable?>()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingGameSessionStart && granted) {
            pendingGameSessionStart = false
            gameProfileViewModel.startSession()
        } else if (pendingGameSessionStart) {
            pendingGameSessionStart = false
            showSnackbar(getString(R.string.notification_permission_required))
        }
    }

    private val stateListener: (ShizukuManager.State) -> Unit = { state ->
        renderShizukuState(state)
        gameProfileViewModel.refreshEnvironment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureBranding()
        applySystemBarInsets()
        attachDensityPanel()
        attachGameProfilePanel()

        configurePreferences()
        configureActions()
        configureGameProfileActions()
        observeUiState()
        ShizukuManager.addStateListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        renderAccessibilityState()
        ShizukuManager.refresh()
        densityViewModel.refresh()
        gameProfileViewModel.refreshEnvironment()
    }

    override fun onDestroy() {
        ShizukuManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    private fun configureBranding() {
        decodeImage(ImageAssets.BACKGROUND_BASE64)?.let { bitmap ->
            binding.backgroundImage.setImageBitmap(bitmap)
        }
        binding.headerLogo.apply {
            setImageResource(R.drawable.zaid_logo)
            setBackgroundResource(R.drawable.bg_logo_clip)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
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

    private fun attachDensityPanel() {
        densityBinding = ViewDensityPanelBinding.inflate(layoutInflater)
        val testCard = binding.buttonTest.parent as View
        val contentContainer = testCard.parent as ViewGroup
        val testIndex = contentContainer.indexOfChild(testCard)
        contentContainer.addView(densityBinding.root, testIndex)
    }

    private fun attachGameProfilePanel() {
        gameProfileBinding = ViewGameProfilePanelBinding.inflate(layoutInflater)
        val testCard = binding.buttonTest.parent as View
        val contentContainer = testCard.parent as ViewGroup
        val testIndex = contentContainer.indexOfChild(testCard)
        contentContainer.addView(gameProfileBinding.root, testIndex)
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
            gameProfileViewModel.refreshEnvironment()
        }

        binding.buttonAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.buttonTest.setOnClickListener {
            if (isGameOperationBusy(latestGameProfileState)) {
                showSnackbar(getString(R.string.operation_in_progress))
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                showMessage(getString(R.string.test_accessibility_notice))
            }
            executeTest()
        }

        binding.buttonInstagram.setOnClickListener {
            openInstagramProfile()
        }

        densityBinding.presetUltra.setOnClickListener {
            if (isGameOperationBusy(latestGameProfileState)) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showUltraConfirmation()
        }
        densityBinding.presetHigh.setOnClickListener {
            if (isGameOperationBusy(latestGameProfileState)) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            densityViewModel.applyPreset(DensityPreset.HIGH)
        }
        densityBinding.presetLow.setOnClickListener {
            if (isGameOperationBusy(latestGameProfileState)) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            densityViewModel.applyPreset(DensityPreset.LOW)
        }
        densityBinding.buttonEmergencyReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (latestGameProfileState.sessionActive) {
                gameProfileViewModel.restoreNow()
            } else if (!isGameOperationBusy(latestGameProfileState)) {
                densityViewModel.resetDensity()
            }
        }
    }

    private fun configureGameProfileActions() {
        gameProfileBinding.gameFreeFire.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.selectGame(SupportedGame.FREE_FIRE)
        }
        gameProfileBinding.gameFreeFireMax.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.selectGame(SupportedGame.FREE_FIRE_MAX)
        }
        gameProfileBinding.gamePresetUltra.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.selectPreset(DensityPreset.ULTRA)
        }
        gameProfileBinding.gamePresetHigh.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.selectPreset(DensityPreset.HIGH)
        }
        gameProfileBinding.gamePresetLow.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.selectPreset(DensityPreset.LOW)
        }
        gameProfileBinding.buttonStartGameSession.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            requestGameSessionStart()
        }
        gameProfileBinding.buttonRestoreGameSessionNow.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            gameProfileViewModel.restoreNow()
        }
    }

    private fun requestGameSessionStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGameSessionStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        gameProfileViewModel.startSession()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    densityViewModel.uiState.collect(::renderDensityState)
                }
                launch {
                    densityViewModel.events.collect(::showSnackbar)
                }
                launch {
                    gameProfileViewModel.uiState.collect(::renderGameProfileState)
                }
                launch {
                    gameProfileViewModel.events.collect(::showSnackbar)
                }
            }
        }
    }

    private fun renderDensityState(state: DensityUiState) {
        latestDensityState = state
        densityBinding.densityStatus.text = state.statusLabel
        densityBinding.densityCurrentValue.text = state.currentDensity?.let {
            getString(R.string.current_density_value, it)
        } ?: getString(R.string.current_density_unknown)

        renderSelection(
            densityBinding.presetUltra,
            state.activePreset == DensityPreset.ULTRA
        )
        renderSelection(
            densityBinding.presetHigh,
            state.activePreset == DensityPreset.HIGH
        )
        renderSelection(
            densityBinding.presetLow,
            state.activePreset == DensityPreset.LOW
        )

        densityBinding.presetUltraState.text = presetStateText(
            state.activePreset == DensityPreset.ULTRA
        )
        densityBinding.presetHighState.text = presetStateText(
            state.activePreset == DensityPreset.HIGH
        )
        densityBinding.presetLowState.text = presetStateText(
            state.activePreset == DensityPreset.LOW
        )

        densityBinding.densityProgressContainer.visibility =
            if (state.isApplying || state.isRefreshing) View.VISIBLE else View.GONE
        densityBinding.densityProgressText.text = if (state.isApplying) {
            getString(R.string.applying_configuration)
        } else {
            getString(R.string.reading_density)
        }

        val persistentError = state.operationMessage.takeIf { it.isNotBlank() }
        densityBinding.densityOperationMessage.visibility =
            if (persistentError == null) View.GONE else View.VISIBLE
        densityBinding.densityOperationMessage.text = persistentError.orEmpty()

        densityBinding.densityLastChange.text = state.lastChangedAt?.let { timestamp ->
            getString(
                R.string.last_density_change,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(timestamp))
            )
        } ?: getString(R.string.no_density_changes)

        updateGlobalControlLocks()
    }

    private fun renderGameProfileState(state: GameProfileUiState) {
        latestGameProfileState = state
        val locked = isGameOperationBusy(state)

        renderGameCard(
            game = SupportedGame.FREE_FIRE,
            card = gameProfileBinding.gameFreeFire,
            check = gameProfileBinding.gameFreeFireCheck,
            status = gameProfileBinding.gameFreeFireInstalledState,
            icon = gameProfileBinding.gameFreeFireIcon,
            state = state,
            locked = locked
        )
        renderGameCard(
            game = SupportedGame.FREE_FIRE_MAX,
            card = gameProfileBinding.gameFreeFireMax,
            check = gameProfileBinding.gameFreeFireMaxCheck,
            status = gameProfileBinding.gameFreeFireMaxInstalledState,
            icon = gameProfileBinding.gameFreeFireMaxIcon,
            state = state,
            locked = locked
        )

        setSectionVisible(
            gameProfileBinding.gameDensitySelectorContainer,
            state.selectedGame != null && !state.sessionActive
        )

        renderProfileCard(
            gameProfileBinding.gamePresetUltra,
            gameProfileBinding.gamePresetUltraCheck,
            state.selectedPreset == DensityPreset.ULTRA,
            locked
        )
        renderProfileCard(
            gameProfileBinding.gamePresetHigh,
            gameProfileBinding.gamePresetHighCheck,
            state.selectedPreset == DensityPreset.HIGH,
            locked
        )
        renderProfileCard(
            gameProfileBinding.gamePresetLow,
            gameProfileBinding.gamePresetLowCheck,
            state.selectedPreset == DensityPreset.LOW,
            locked
        )

        val preparing = state.currentStep in PREPARING_STEPS
        val restoring = state.currentStep == SessionStep.RESTORING_DENSITY
        gameProfileBinding.buttonStartGameSession.text = when {
            restoring -> getString(R.string.game_session_restoring)
            preparing -> getString(R.string.game_session_preparing)
            state.sessionActive -> getString(
                R.string.session_active_seconds,
                state.secondsRemaining ?: 0
            )
            state.selectedGame == null -> getString(R.string.select_a_game)
            state.selectedPreset == null -> getString(R.string.select_a_profile)
            else -> getString(
                R.string.start_game_with_profile,
                state.selectedGame.displayName,
                state.selectedPreset.displayName
            )
        }
        gameProfileBinding.buttonStartGameSession.isEnabled = state.canStart
        gameProfileBinding.gameSessionStartProgress.visibility =
            if (preparing || restoring) View.VISIBLE else View.GONE

        val showProgressCard = state.sessionActive || preparing || restoring
        setSectionVisible(gameProfileBinding.sessionProgressCard, showProgressCard)
        if (showProgressCard) renderSessionProgress(state)

        gameProfileBinding.gameProfileError.visibility =
            if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        gameProfileBinding.gameProfileError.text = state.errorMessage.orEmpty()

        updateGlobalControlLocks()
    }

    private fun renderGameCard(
        game: SupportedGame,
        card: View,
        check: View,
        status: TextView,
        icon: ImageView,
        state: GameProfileUiState,
        locked: Boolean
    ) {
        val installed = game in state.installedGames
        val selected = state.selectedGame == game
        card.isEnabled = installed && !locked
        card.alpha = if (installed) 1f else 0.52f
        status.text = getString(
            if (installed) R.string.game_installed else R.string.game_not_installed
        )
        status.setTextColor(
            color(if (installed) R.color.status_success else R.color.status_warning)
        )
        renderSelection(card, selected)
        animateCheck(check, selected)
        loadGameIcon(icon, game, installed)
    }

    private fun loadGameIcon(
        imageView: ImageView,
        game: SupportedGame,
        installed: Boolean
    ) {
        if (!installed) {
            imageView.setImageResource(R.drawable.ic_game_placeholder)
            imageView.tag = null
            return
        }
        if (imageView.tag == game.packageName) return
        val drawable = gameIconCache.getOrPut(game) {
            runCatching {
                packageManager.getApplicationIcon(game.packageName)
            }.getOrNull()
        }
        if (drawable != null) {
            imageView.setImageDrawable(drawable)
            imageView.tag = game.packageName
        } else {
            imageView.setImageResource(R.drawable.ic_game_placeholder)
        }
    }

    private fun renderProfileCard(
        card: View,
        check: View,
        selected: Boolean,
        locked: Boolean
    ) {
        card.isEnabled = !locked
        card.alpha = if (locked) 0.72f else 1f
        renderSelection(card, selected)
        animateCheck(check, selected)
    }

    private fun renderSelection(view: View, selected: Boolean) {
        if (view.isSelected == selected) return
        view.isSelected = selected
        view.animate().cancel()
        view.animate()
            .scaleX(if (selected) 1.015f else 1f)
            .scaleY(if (selected) 1.015f else 1f)
            .setDuration(180L)
            .setInterpolator(OvershootInterpolator(0.7f))
            .start()
    }

    private fun animateCheck(view: View, visible: Boolean) {
        val currentlyVisible = view.visibility == View.VISIBLE
        if (visible == currentlyVisible) return
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.scaleX = 0.65f
            view.scaleY = 0.65f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .scaleX(0.65f)
                .scaleY(0.65f)
                .setDuration(120L)
                .withEndAction { view.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun setSectionVisible(view: View, visible: Boolean) {
        val shouldBeVisible = view.visibility == View.VISIBLE
        if (shouldBeVisible == visible) return
        TransitionManager.beginDelayedTransition(
            gameProfileBinding.gameProfilePanelRoot,
            AutoTransition().apply { duration = 220L }
        )
        if (visible) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = 12f * resources.displayMetrics.density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        } else {
            view.visibility = View.GONE
        }
    }

    private fun renderSessionProgress(state: GameProfileUiState) {
        val game = state.selectedGame
        val preset = state.selectedPreset
        gameProfileBinding.sessionProgressTitle.text = when (state.currentStep) {
            SessionStep.SESSION_ACTIVE -> getString(R.string.game_session_active)
            SessionStep.RESTORING_DENSITY -> getString(R.string.game_session_restoring)
            else -> getString(R.string.game_session_preparing)
        }
        gameProfileBinding.sessionProgressSubtitle.text = if (game != null && preset != null) {
            getString(
                R.string.session_game_profile_format,
                game.displayName,
                preset.displayName,
                preset.density
            )
        } else {
            getString(R.string.game_session_checking_state)
        }

        val countdownVisible =
            state.sessionActive && state.currentStep == SessionStep.SESSION_ACTIVE
        gameProfileBinding.sessionCountdownContainer.visibility =
            if (countdownVisible) View.VISIBLE else View.GONE
        if (countdownVisible) {
            val seconds = (state.secondsRemaining ?: 0).coerceIn(0, 30)
            gameProfileBinding.sessionSeconds.text = seconds.toString()
            gameProfileBinding.sessionCountdownRing.setProgressCompat(seconds, true)
        }

        val step = state.currentStep
        renderStep(
            gameProfileBinding.sessionStepSaved,
            label = "DPI guardado",
            completed = step.ordinal >= SessionStep.CLOSING_GAME.ordinal,
            current = step == SessionStep.SAVING_DENSITY
        )
        renderStep(
            gameProfileBinding.sessionStepRestarted,
            label = "Juego reiniciado",
            completed = step.ordinal >= SessionStep.SESSION_ACTIVE.ordinal,
            current = step in setOf(SessionStep.CLOSING_GAME, SessionStep.OPENING_GAME)
        )
        renderStep(
            gameProfileBinding.sessionStepActive,
            label = "Sesión activa",
            completed = step in setOf(SessionStep.RESTORING_DENSITY, SessionStep.COMPLETED),
            current = step == SessionStep.SESSION_ACTIVE
        )
        renderStep(
            gameProfileBinding.sessionStepRestore,
            label = "Restaurar DPI",
            completed = step == SessionStep.COMPLETED,
            current = step == SessionStep.RESTORING_DENSITY || step == SessionStep.ERROR
        )

        gameProfileBinding.buttonRestoreGameSessionNow.visibility =
            if (state.sessionActive) View.VISIBLE else View.GONE
        gameProfileBinding.buttonRestoreGameSessionNow.isEnabled =
            state.currentStep != SessionStep.RESTORING_DENSITY
    }

    private fun renderStep(
        view: TextView,
        label: String,
        completed: Boolean,
        current: Boolean
    ) {
        val newText = "${when {
            completed -> "✓"
            current -> "●"
            else -> "○"
        }} $label"
        val newColor = color(
            when {
                completed -> R.color.status_success
                current -> R.color.glass_accent_secondary
                else -> R.color.glass_text_secondary
            }
        )
        if (view.text.toString() == newText && view.currentTextColor == newColor) return
        view.animate().cancel()
        view.alpha = 0.55f
        view.text = newText
        view.setTextColor(newColor)
        view.animate().alpha(1f).setDuration(160L).start()
    }

    private fun updateGlobalControlLocks() {
        if (!::densityBinding.isInitialized || !::gameProfileBinding.isInitialized) return
        val gameBusy = isGameOperationBusy(latestGameProfileState)
        val densityBusy = latestDensityState.isApplying || latestDensityState.isRefreshing
        val densitySelectionEnabled = !gameBusy && !densityBusy

        densityBinding.presetUltra.isEnabled = densitySelectionEnabled
        densityBinding.presetHigh.isEnabled = densitySelectionEnabled
        densityBinding.presetLow.isEnabled = densitySelectionEnabled
        densityBinding.buttonEmergencyReset.isEnabled = when {
            latestGameProfileState.sessionActive ->
                latestGameProfileState.currentStep != SessionStep.RESTORING_DENSITY
            gameBusy -> false
            else -> !densityBusy
        }
        densityBinding.buttonEmergencyReset.text = getString(
            if (latestGameProfileState.sessionActive) {
                R.string.restore_now
            } else {
                R.string.emergency_reset_density
            }
        )
        binding.buttonTest.isEnabled = !gameBusy && !densityBusy
    }

    private fun isGameOperationBusy(state: GameProfileUiState): Boolean =
        state.sessionActive || state.currentStep in BUSY_SESSION_STEPS

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

        dialogBinding.buttonCancelUltra.setOnClickListener {
            resetHoldState()
            dialog.dismiss()
        }
        dialog.setOnDismissListener { resetHoldState() }

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
            binding.testResult.text = if (result.success) {
                "✓ Restauración de emergencia configurada"
            } else {
                buildString {
                    append(result.message)
                    if (result.stderr.isNotBlank()) {
                        append('\n')
                        append(result.stderr)
                    } else if (result.stdout.isNotBlank()) {
                        append('\n')
                        append(result.stdout)
                    }
                }
            }
            binding.testResult.setTextColor(
                color(if (result.success) R.color.status_success else R.color.status_error)
            )
            if (result.success) {
                densityViewModel.recordExternalReset()
            } else {
                showMessage(result.message)
            }
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
        view: TextView,
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

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val ULTRA_CONFIRM_HOLD_MILLIS = 1_500L

        val PREPARING_STEPS = setOf(
            SessionStep.VALIDATING,
            SessionStep.SAVING_DENSITY,
            SessionStep.CLOSING_GAME,
            SessionStep.APPLYING_DENSITY,
            SessionStep.VERIFYING_DENSITY,
            SessionStep.OPENING_GAME
        )

        val BUSY_SESSION_STEPS = PREPARING_STEPS + setOf(
            SessionStep.SESSION_ACTIVE,
            SessionStep.RESTORING_DENSITY
        )
    }
}
