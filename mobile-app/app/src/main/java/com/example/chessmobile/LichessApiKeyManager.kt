package com.example.chessmobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for the Lichess API token using AES-256 encrypted SharedPreferences.
 * The key is stored encrypted on disk and is never exposed in logs or plain text storage.
 */
object LichessApiKeyManager {

    private const val PREFS_FILE = "lichess_secure_prefs"
    private const val KEY_API_TOKEN = "api_token"

    private fun getPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_TOKEN, key.trim()).apply()
    }

    fun getApiKey(context: Context): String? =
        getPrefs(context).getString(KEY_API_TOKEN, null)

    fun hasApiKey(context: Context): Boolean =
        getApiKey(context)?.isNotBlank() == true

    fun clearApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_API_TOKEN).apply()
    }
}
