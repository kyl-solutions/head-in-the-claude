package com.kylsolutions.kylidescope

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages secure storage of the Anthropic API key.
 * Uses EncryptedSharedPreferences backed by Android Keystore.
 */
class ApiKeyManager(context: Context) {

    companion object {
        private const val PREFS_FILENAME = "kylidescope_secure_prefs"
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val KEY_MODEL = "selected_model"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save the API key securely.
     */
    fun saveApiKey(apiKey: String) {
        encryptedPrefs.edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    /**
     * Retrieve the stored API key.
     */
    fun getApiKey(): String? {
        return encryptedPrefs.getString(KEY_API_KEY, null)
    }

    /**
     * Check if an API key is stored.
     */
    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    /**
     * Clear the stored API key.
     */
    fun clearApiKey() {
        encryptedPrefs.edit()
            .remove(KEY_API_KEY)
            .apply()
    }

    /**
     * Save the selected model.
     */
    fun saveModel(model: String) {
        encryptedPrefs.edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    /**
     * Get the selected model (defaults to Sonnet 4.5).
     */
    fun getModel(): String {
        return encryptedPrefs.getString(KEY_MODEL, "claude-sonnet-4-5-20250929")
            ?: "claude-sonnet-4-5-20250929"
    }

    /**
     * Validate API key format (basic check).
     */
    fun isValidApiKeyFormat(apiKey: String): Boolean {
        // Anthropic API keys start with "sk-ant-api03-"
        return apiKey.startsWith("sk-ant-api") && apiKey.length >= 50
    }
}
