package com.zaid.densityreset.startup

import com.zaid.densityreset.update.AppRelease

sealed interface StartupGate {
    data object Checking : StartupGate
    data object Ready : StartupGate
    data class UpdateRequired(val release: AppRelease) : StartupGate
    data class Maintenance(val message: String) : StartupGate
    data object LicenseRequired : StartupGate
    data class Error(val message: String) : StartupGate
}

enum class StartupDestination {
    GAME_LAUNCHER,
    LEGACY_CONTROLS
}
