package app.shunt.app.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.shunt.app.plan.Destination
import app.shunt.app.plan.Favorites
import app.shunt.app.plan.FavoritesStore
import app.shunt.app.plan.LocationProvider
import app.shunt.core.GeoPoint

/** Home/Work favorites persisted in SharedPreferences as "title|lat|lon". */
class SharedPrefsFavoritesStore(context: Context) : FavoritesStore {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    override fun load(): Favorites =
        Favorites(home = read(KEY_HOME), work = read(KEY_WORK))

    override fun save(favorites: Favorites) {
        prefs.edit().apply {
            write(KEY_HOME, favorites.home)
            write(KEY_WORK, favorites.work)
        }.apply()
    }

    private fun read(key: String): Destination? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 3) return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        return runCatching { Destination(parts[0], GeoPoint(lat, lon)) }.getOrNull()
    }

    private fun android.content.SharedPreferences.Editor.write(key: String, dest: Destination?) {
        if (dest == null) remove(key)
        else putString(key, "${dest.title}|${dest.location.lat}|${dest.location.lon}")
    }

    private companion object {
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
    }
}

/**
 * Origin for routing and the autosuggest bias. Uses the device's last-known
 * location when ACCESS_FINE_LOCATION is already granted, otherwise falls back
 * to the saved Home favorite. Live fused-location tracking (and the permission
 * request) arrives with the M4 drive monitor; M3 does not request permissions.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val favorites: FavoritesStore,
) : LocationProvider {

    override suspend fun currentOrigin(): GeoPoint? =
        lastKnownLocation() ?: favorites.load().home?.location

    private fun lastKnownLocation(): GeoPoint? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let { runCatching { GeoPoint(it.latitude, it.longitude) }.getOrNull() }
    }
}
