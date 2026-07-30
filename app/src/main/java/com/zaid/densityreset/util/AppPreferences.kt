package com.zaid.densityreset.util

import android.content.Context

object AppPreferences {
    private const val FILE_NAME = "density_reset_preferences"
    private const val KEY_BLOCK_VOLUME = "block_volume_changes"
    private const val KEY_VIBRATE_SUCCESS = "vibrate_after_success"

    fun shouldBlockVolumeChanges(context: Context): Boolean =
        preferences(context).getBoolean(KEY_BLOCK_VOLUME, false)

    fun setBlockVolumeChanges(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_BLOCK_VOLUME, enabled).apply()
    }

    fun shouldVibrateAfterSuccess(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VIBRATE_SUCCESS, true)

    fun setVibrateAfterSuccess(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_VIBRATE_SUCCESS, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
