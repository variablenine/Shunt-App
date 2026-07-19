package app.shunt.core

/**
 * A WGS84 coordinate. Latitude in [-90, 90], longitude in [-180, 180].
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude $lat out of range" }
        require(lon in -180.0..180.0) { "longitude $lon out of range" }
    }
}
