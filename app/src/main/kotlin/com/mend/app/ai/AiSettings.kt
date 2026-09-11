package com.mend.app.ai

import android.content.Context

/**
 * Where the API key lives.
 *
 * Plain SharedPreferences, and worth being honest about what that means: a key
 * stored on a device can be extracted from it by anyone holding the device
 * unlocked, and a key compiled into an APK can be extracted by anyone at all.
 * Neither is acceptable for a key that is not the user's own.
 *
 * For a personal app where the owner supplies their own key and pays their own
 * bill, that is a reasonable position. It stops being reasonable the moment
 * Mend ships to anyone else with a key in it — which is why there is no default
 * value here and no key anywhere in the repository.
 */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The user's own Anthropic API key, or null when none is set. */
    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value?.trim()).apply()
        }

    /** Whether the AI layer should be used at all, independent of the key. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    val hasApiKey: Boolean get() = apiKey != null

    /** Shows enough of the key to recognise it, and no more. */
    fun maskedKey(): String? = apiKey?.let { key ->
        if (key.length <= VISIBLE_CHARS) "…" else "…${key.takeLast(VISIBLE_CHARS)}"
    }

    private companion object {
        const val FILE = "mend_ai_settings"
        const val KEY_API_KEY = "anthropic_api_key"
        const val KEY_ENABLED = "ai_enabled"
        const val VISIBLE_CHARS = 6
    }
}
