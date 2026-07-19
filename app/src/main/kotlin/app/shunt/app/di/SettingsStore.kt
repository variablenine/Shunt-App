package app.shunt.app.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-entered settings persisted on-device. The HERE API key lives here so a
 * shippable APK need not embed one: enter it in the app and it applies
 * immediately (the HERE clients read the key through a provider). Stored in
 * plain SharedPreferences — adequate for a key scoped to the user's own device.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _hereApiKey = MutableStateFlow(prefs.getString(KEY_HERE, "").orEmpty())
    val hereApiKey: StateFlow<String> = _hereApiKey.asStateFlow()

    fun setHereApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_HERE, trimmed).apply()
        _hereApiKey.value = trimmed
    }

    private companion object {
        const val KEY_HERE = "here_api_key"
    }
}
