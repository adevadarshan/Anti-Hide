package com.antihide.v2.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Session slots at rest in Keystore-encrypted preferences, so a paired browser survives a service
 * restart (experiment E-10) without ever writing a credential to plain storage.
 */
class EncryptedSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun readAll(): List<StoredSession> =
        prefs.getString(KEY, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { StoredSession.decode(it) }

    override fun writeAll(sessions: List<StoredSession>) {
        prefs.edit().putString(KEY, sessions.joinToString("\n") { it.encode() }).apply()
    }

    private companion object {
        const val FILE_NAME = "antihide_sessions"
        const val KEY = "session_slots"
    }
}

/**
 * Per-device HMAC key held in the Android Keystore. The key bytes are never exported, not even to
 * this process, so CSRF tokens cannot be recomputed off-device.
 */
class KeystoreCsrfKeyProvider : CsrfKeyProvider {

    override fun hmacSha256(input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(loadOrCreateKey()) }.doFinal(input)

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "antihide_v2_csrf_hmac"
    }
}