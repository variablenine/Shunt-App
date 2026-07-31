package app.shunt.app.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.shunt.app.plan.Destination
import app.shunt.app.plan.Favorites
import app.shunt.app.plan.FavoritesStore
import app.shunt.app.plan.RecentPlacesStore
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
 * Recently-routed-to places, newest first.
 *
 * Stored as plain app-private prefs like the favorites, and capped: this is a
 * convenience, not a history feature, and a short list is both easier to pick
 * from and less of a record of where someone has been. Kept out of backups by
 * the app's `allowBackup="false"`.
 */
class SharedPrefsRecentPlacesStore(context: Context) : RecentPlacesStore {
    private val prefs = context.getSharedPreferences("recent_places", Context.MODE_PRIVATE)

    override fun load(): List<Destination> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull(::parse)
    }

    override fun record(destination: Destination) {
        // Same place again just moves to the front rather than piling up.
        val updated = (listOf(destination) + load().filterNot { it.location == destination.location })
            .take(MAX_RECENTS)
        prefs.edit()
            .putString(KEY_RECENTS, updated.joinToString(RECORD_SEPARATOR, transform = ::format))
            .apply()
    }

    /** Clear the list — the user's own record of where they have been. */
    fun clear() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun format(d: Destination): String =
        "${d.title.replace(FIELD_SEPARATOR, " ").replace(RECORD_SEPARATOR, " ")}" +
            "$FIELD_SEPARATOR${d.location.lat}$FIELD_SEPARATOR${d.location.lon}"

    private fun parse(raw: String): Destination? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size != 3) return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        return runCatching { Destination(parts[0], GeoPoint(lat, lon)) }.getOrNull()
    }

    private companion object {
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 6
        const val FIELD_SEPARATOR = "|"
        const val RECORD_SEPARATOR = "\n"
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
        lastKnownFix()?.let { runCatching { GeoPoint(it.latitude, it.longitude) }.getOrNull() }
            ?: favorites.load().home?.location

    /**
     * The bearing we're travelling on, or null unless the fix says we are
     * genuinely under way. A parked car's last bearing is whatever direction it
     * happened to stop facing — routing from it would forbid the road behind
     * for no reason — and a stale fix says nothing about now.
     */
    override suspend fun currentHeading(): Double? {
        val fix = lastKnownFix() ?: return null
        if (!fix.hasBearing()) return null
        if (!fix.hasSpeed() || fix.speed < MOVING_METERS_PER_SEC) return null
        val age = System.currentTimeMillis() - fix.time
        if (age > MAX_FIX_AGE_MILLIS) return null
        return fix.bearing.toDouble()
    }

    private fun lastKnownFix(): android.location.Location? {
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
    }

    private companion object {
        /** At or above this the car is under way, so its heading means something. */
        const val MOVING_METERS_PER_SEC = 2.0f

        /** Older than this and "which way am I pointing" is anyone's guess. */
        const val MAX_FIX_AGE_MILLIS = 30_000L
    }
}
