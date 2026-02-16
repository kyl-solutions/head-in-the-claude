package com.kylsolutions.hitc

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists session info and relay settings across app restarts.
 */
class HitcSessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hitc_sessions", Context.MODE_PRIVATE)

    var currentSessionId: String?
        get() = prefs.getString("current_session_id", null)
        set(value) = prefs.edit().putString("current_session_id", value).apply()

    var relayUrl: String
        get() = prefs.getString("relay_url", DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
        set(value) = prefs.edit().putString("relay_url", value).apply()

    var authToken: String
        get() = prefs.getString("auth_token", DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
        set(value) = prefs.edit().putString("auth_token", value).apply()

    fun newSession() {
        currentSessionId = null
    }

    companion object {
        const val DEFAULT_RELAY_URL = "http://100.90.48.63:3847"
        const val DEFAULT_AUTH_TOKEN = "17ec609459cc4ffd456319fd6939923886fa133db6dc8e62996281cdf3651281"
    }
}
