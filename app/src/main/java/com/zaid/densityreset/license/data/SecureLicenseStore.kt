package com.zaid.densityreset.license.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class SecureLicenseSession(
    val token: String,
    val deviceHash: String,
    val tokenExpiresAtEpochMillis: Long?
)

class SecureLicenseStore(
    context: Context
) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(session: SecureLicenseSession) {
        val payload = JSONObject()
            .put("token", session.token)
            .put("deviceHash", session.deviceHash)
            .put(
                "tokenExpiresAtEpochMillis",
                session.tokenExpiresAtEpochMillis ?: JSONObject.NULL
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)

        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun read(): SecureLicenseSession? = runCatching {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val json = JSONObject(
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
        )
        SecureLicenseSession(
            token = json.getString("token"),
            deviceHash = json.getString("deviceHash"),
            tokenExpiresAtEpochMillis = json.optLong(
                "tokenExpiresAtEpochMillis",
                Long.MIN_VALUE
            ).takeUnless { it == Long.MIN_VALUE }
        )
    }.getOrElse {
        clear()
        null
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "density_reset_license_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES_NAME = "density_reset_license_secure"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
    }
}
