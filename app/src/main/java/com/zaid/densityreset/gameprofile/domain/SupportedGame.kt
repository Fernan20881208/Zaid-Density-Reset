package com.zaid.densityreset.gameprofile.domain

enum class SupportedGame(
    val displayName: String,
    val packageName: String
) {
    FREE_FIRE(
        displayName = "Free Fire",
        packageName = "com.dts.freefireth"
    ),
    FREE_FIRE_MAX(
        displayName = "Free Fire MAX",
        packageName = "com.dts.freefiremax"
    );

    companion object {
        fun fromPackageName(packageName: String?): SupportedGame? =
            entries.firstOrNull { it.packageName == packageName }
    }
}
