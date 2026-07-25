package app.shunt.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's own Tessie credentials. Blank [token] or [vin] means unconfigured. */
data class VehicleCredentials(val token: String = "", val vin: String = "") {
    val isConfigured: Boolean get() = token.isNotBlank() && vin.isNotBlank()
}

/**
 * On-device storage for the Tessie token and VIN.
 *
 * These are materially more sensitive than an ordinary API key: a Tessie token
 * can command the user's car. So they are held in [EncryptedSharedPreferences]
 * under a Keystore-backed master key — app-private storage alone would leave
 * them readable from a rooted device or an offline image of it. The manifest
 * also sets `allowBackup="false"`, so they never leave the device in a cloud
 * backup.
 *
 * If the Keystore is unavailable (a known failure mode on a handful of OEM
 * builds, and after certain device-state changes) we fall back to plain
 * private preferences rather than locking the user out of their own car:
 * [usingEncryptedStorage] reports which is in force so the UI can be honest
 * about it.
 */
class VehicleCredentialsStore(context: Context) {

    private val appContext = context.applicationContext
    var usingEncryptedStorage: Boolean = true
        private set

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "vehicle-credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        usingEncryptedStorage = false
        appContext.getSharedPreferences("vehicle-credentials-plain", Context.MODE_PRIVATE)
    }

    private val _credentials = MutableStateFlow(
        VehicleCredentials(
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            vin = prefs.getString(KEY_VIN, "").orEmpty(),
        ),
    )
    val credentials: StateFlow<VehicleCredentials> = _credentials.asStateFlow()

    fun save(token: String, vin: String) {
        // VINs are conventionally upper-case; accept either and normalise so a
        // lower-case paste doesn't silently fail against the API.
        val cleaned = VehicleCredentials(token.trim(), vin.trim().uppercase())
        prefs.edit().putString(KEY_TOKEN, cleaned.token).putString(KEY_VIN, cleaned.vin).apply()
        _credentials.value = cleaned
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_VIN).apply()
        _credentials.value = VehicleCredentials()
    }

    private companion object {
        const val KEY_TOKEN = "tessie_token"
        const val KEY_VIN = "tessie_vin"
    }
}
