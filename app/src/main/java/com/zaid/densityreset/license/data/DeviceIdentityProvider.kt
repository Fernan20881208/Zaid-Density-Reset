package com.zaid.densityreset.license.data

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

interface DeviceIdentityProvider {
    fun getDeviceHash(): String
}

class AndroidDeviceIdentityProvider(
    private val context: Context
) : DeviceIdentityProvider {

    override fun getDeviceHash(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val material = buildString {
            append(androidId)
            append('|')
            append(context.packageName)
            append('|')
            append(APP_SPECIFIC_SALT)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val APP_SPECIFIC_SALT = "density-reset-device-v1"
    }
}
