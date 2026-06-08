package com.example.chessmobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for the Lichess API token using AES-256 encrypted SharedPreferences.
 * Falls back to plain SharedPreferences if the KeyStore is unavailable (e.g. on some emulators).
 */
object LichessApiKeyManager {

    private const val PREFS_FILE = "lichess_secure_prefs"
    private const val PREFS_FILE_PLAIN = "lichess_prefs_fallback"
    private const val KEY_API_TOKEN = "api_token"

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("LichessApiKeyManager", "EncryptedSharedPreferences unavailable, using plain fallback: ${e.message}")
            context.getSharedPreferences(PREFS_FILE_PLAIN, Context.MODE_PRIVATE)
        }
    }

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
