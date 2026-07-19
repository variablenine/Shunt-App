package app.shunt.solver.camera

import app.shunt.core.GeoPoint

/**
 * An ALPR camera record from the DeFlock dataset (OSM-derived, ODbL —
 * see LICENSE-DATA.md).
 */
data class Camera(
    val id: Long,
    val location: GeoPoint,
    val tags: Map<String, String> = emptyMap(),
) {
    /**
     * The direction the camera faces, in degrees clockwise from north, if
     * tagged. Live data (checked 2026-07) carries plain `direction` on ~97%
     * of records and `camera:direction` on ~2%; we honor both, preferring
     * the more specific OSM key. Values may be numeric or cardinal ("NE").
     */
    val directionDegrees: Double? by lazy {
        val raw = tags["camera:direction"] ?: tags["direction"] ?: return@lazy null
        parseDirection(raw)
    }

    companion object {
        private val CARDINALS = mapOf(
            "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
            "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
            "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
            "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
        )

        /** Parse an OSM direction value; null for unparseable or multi-valued tags. */
        fun parseDirection(raw: String): Double? {
            val v = raw.trim().uppercase()
            CARDINALS[v]?.let { return it }
            return v.toDoubleOrNull()?.let { ((it % 360.0) + 360.0) % 360.0 }
        }
    }
}
