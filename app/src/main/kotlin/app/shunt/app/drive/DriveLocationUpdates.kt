package app.shunt.app.drive

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.shunt.core.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS fixes as a cold [Flow]. Caller must hold ACCESS_FINE_LOCATION (the
 * service checks before subscribing). Uses the platform LocationManager
 * directly — no Play Services dependency. Updates stop when the flow is
 * cancelled, so nothing runs once the drive ends.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(
    context: Context,
    minIntervalMillis: Long = 1_000L,
    minDistanceMeters: Float = 0f,
): Flow<LocationUpdate> = callbackFlow {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val listener = LocationListener { location -> trySend(location.toUpdate()) }

    val providers = buildList {
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    providers.forEach { provider ->
        manager.requestLocationUpdates(provider, minIntervalMillis, minDistanceMeters, listener, Looper.getMainLooper())
    }

    awaitClose { manager.removeUpdates(listener) }
}

private fun Location.toUpdate(): LocationUpdate = LocationUpdate(
    point = GeoPoint(latitude, longitude),
    speedMetersPerSec = if (hasSpeed()) speed.toDouble() else null,
    bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
)
