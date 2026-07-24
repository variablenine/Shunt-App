package app.shunt.solver.search

import app.shunt.core.GeoPoint

/** A destination suggestion with a resolved coordinate. */
data class Suggestion(
    val title: String,
    val location: GeoPoint,
    val resultType: String,
)
