package app.shunt.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shunt.R
import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.destinationPoint
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * A camera to draw on the map: its position, which way it faces (if known),
 * and a short human label. Built from the DeFlock/OSM record upstream so this
 * UI layer stays independent of tag-parsing details.
 */
data class MapCamera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val directionDegrees: Double?,
    val title: String,
    val subtitle: String?,
    /**
     * How far this camera is treated as seeing, relative to the built-in
     * estimate — the same scale the router used.
     *
     * Carried on the camera rather than read from a setting at draw time so the
     * cone on the map is always the shape the route was planned against. A map
     * that draws a different reach from the one the avoidance used is worse than
     * no cone at all: it makes a correct route look wrong.
     */
    val rangeScale: Double = 1.0,
)

/**
 * A charging site the user can tap to put on the trip.
 *
 * The automatic pick uses range arithmetic that is only as good as the numbers
 * behind it — a derate, a reserve, a guess at what a stop puts back. When it
 * picks nothing, or picks somewhere the driver knows is a bad idea, being able
 * to point at one on the map is the fallback that always works.
 */
data class MapCharger(val id: Long, val lat: Double, val lon: Double, val title: String)

/** Plain dark background style used when no basemap URL is configured or it fails to load. */
private const val BLANK_STYLE =
    """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#161826"}}]}"""

private const val ROUTE_SOURCE = "route"
private const val ROUTE_LAYER = "route-line"

// All cameras visible in the current viewport (DeFlock-style display).
private const val CAMERA_SOURCE = "cameras-view"
private const val CAMERA_LAYER = "cameras-view-dots"
private const val CONE_SOURCE = "camera-cones"
private const val CONE_LAYER = "camera-cones-fill"

// The subset of cameras the chosen route passes near — drawn brighter, on top.
private const val PASSED_SOURCE = "cameras-passed"
private const val PASSED_LAYER = "cameras-passed-dots"
private const val CHARGER_SOURCE = "route-chargers"
private const val CHARGER_LAYER = "route-charger-dots"

/**
 * The dashed line from the end of what has been planned to where the trip is
 * actually going. See [renderPending].
 */
private const val PENDING_SOURCE = "route-pending"
private const val PENDING_LAYER = "route-pending-line"

/**
 * The marching-ants cycle for that line, in dash-pattern phases.
 *
 * MapLibre has no dash *offset* to animate, so movement is faked the way
 * Mapbox's own "animate a line" example does it: cycle a set of patterns whose
 * gap walks along the line. Each entry is (dash, gap) in line-widths.
 */
private val PENDING_DASHES: List<Array<Float>> = listOf(
    arrayOf(0f, 4f, 3f),
    arrayOf(0.5f, 4f, 2.5f),
    arrayOf(1f, 4f, 2f),
    arrayOf(1.5f, 4f, 1.5f),
    arrayOf(2f, 4f, 1f),
    arrayOf(2.5f, 4f, 0.5f),
    arrayOf(3f, 4f, 0f),
)

/** How fast the ants march. Slow enough to read as "working", not as urgent. */
private const val PENDING_FRAME_MILLIS = 90L

/**
 * Below this the plan has effectively arrived and there is nothing pending.
 *
 * Generous, because a leg boundary is chosen for being a quiet point on a road
 * near the destination rather than the destination itself, and a few hundred
 * metres of dashes twitching over the final address would look like a fault.
 */
private const val PENDING_MIN_METERS = 1_000.0

/**
 * How long after the driver last moved the map before it resumes framing the
 * drive itself. See [frameDrive].
 *
 * Long enough to look at something — the road ahead, a camera a mile off — and
 * short enough that the map is useful again without being asked. Erring long is
 * the safe direction: taking the map back too early is the app overriding a
 * deliberate action, which is the thing §6.1 is about.
 */
private const val FOLLOW_RESUME_MILLIS = 12_000L

/** How often the follow camera re-frames. Slow: each one is an animation. */
private const val FOLLOW_TICK_MILLIS = 3_000L

/**
 * Padding around the driver-and-next-pin box, as a fraction of the view.
 *
 * Generous at the bottom because the driving sheet covers it, and because what
 * a driver needs to see is the road *ahead* rather than an evenly centred box.
 */
private const val FOLLOW_PADDING_FRACTION = 0.22

/** Never zoom in past this framing two points that are almost the same place. */
private const val FOLLOW_MAX_ZOOM = 16.5

/** Place-name layers Shunt adds to the basemap. See [addPlaceLabels]. */
private const val POI_LAYER = "shunt-poi-labels"
private const val PARK_LAYER = "shunt-park-labels"

/**
 * The dot under each place name, marking where the place actually is.
 *
 * A floating label says roughly *around here*, which on a map you are using to
 * find a turn is not the same thing at all — the name can sit a car's length
 * off, or on the wrong side of a road. Reported as missing: "the little
 * locations that show up on the map should have little dots or something to
 * indicate where they are currently."
 */
private const val POI_DOT_LAYER = "shunt-poi-dots"

/**
 * The vector source the basemap already draws from, and the layers inside it
 * that carry names.
 *
 * These are OpenMapTiles schema names, not ours — the source id comes from the
 * style document and the source-layers from the tile manifest.
 */
private const val BASEMAP_SOURCE = "openmaptiles"
private const val POI_SOURCE_LAYER = "poi"
private const val PARK_SOURCE_LAYER = "park"

/**
 * Zoom at which shop and amenity names appear.
 *
 * The tiles carry them from z11, which is a whole county at once and would be
 * an unreadable wall of text over a route. Fourteen is roughly a
 * neighbourhood, and about where Google starts naming businesses.
 */
private const val POI_MIN_ZOOM = 14.0f

/** Parks are big and few, so they can be named far earlier than a shop can. */
private const val PARK_MIN_ZOOM = 11.0f

/**
 * How prominent a POI must be to be drawn at [POI_MIN_ZOOM].
 *
 * OpenMapTiles ranks POIs within each tile, 1 being the most significant. Two
 * zoom levels further in the limit is lifted entirely — by then there is room
 * for everything the area actually contains.
 */
private const val POI_RANK_LIMIT = 12L

/** The basemap's own one-way arrow layers. See [straightenOneWayArrows]. */
private const val ONE_WAY_LAYER = "road_oneway"
private const val ONE_WAY_REVERSED_LAYER = "road_oneway_opposite"
private const val ONE_WAY_ICON = "shunt-oneway"

/** Nominal size of our arrowhead, matching the sprite it replaces. */
private const val ONE_WAY_ICON_DP = 14f

/**
 * How far apart the arrows sit. Tighter than the style's 200, so a roundabout
 * carries several and reads as circulation rather than one stray mark.
 */
private const val ONE_WAY_SPACING_DP = 90f
private const val NEARBY_SOURCE = "route-nearby-cameras"
private const val NEARBY_LAYER = "route-nearby-camera-dots"
// Where the trip is going, drawn the moment a destination is chosen — before
// any route exists. Planning a long trip takes seconds, and a map that shows
// nothing at all during them reads as an app that did not register the tap.
/** Zoom used when framing a destination that has no route round it yet. */
private const val LONE_PIN_ZOOM = 13.0

private const val DESTINATION_SOURCE = "trip-destination"
private const val DESTINATION_LAYER = "trip-destination-pin"

private const val WAYPOINT_SOURCE = "route-waypoints"
private const val WAYPOINT_LAYER = "route-waypoint-dots"

/**
 * Above this viewport span (~330 km) we stop fetching cameras for the visible
 * area. It used to cut off at ~44 km, which meant that zooming out far enough
 * to see a whole trip made every camera vanish — the exact moment you most want
 * to see what the route is dodging. The tiles are cached and shared with the
 * router, so a wide view costs little beyond the drawing.
 */
private const val MAX_VIEWPORT_SPAN_DEG = 3.0

/**
 * MapLibre map (never the Google Maps SDK) showing the chosen route, the
 * cameras it passes (alarm red), and — when [cameraFetcher] is supplied — every
 * known ALPR in view, DeFlock-style: a dot plus a cone pointing the way each
 * camera faces. Tap a camera for its details. A pulsing blue dot marks the
 * user's current location when location permission is granted.
 */
@Composable
fun RouteMap(
    routePolyline: List<GeoPoint>,
    /**
     * Later legs of a long trip, each as its own line. Drawn as separate
     * features from [routePolyline] so no straight segment is ever drawn
     * between two of them.
     */
    laterLegLines: List<List<GeoPoint>> = emptyList(),
    passedCameras: List<GeoPoint>,
    /**
     * The shaping pins Shunt will send the car through. Worth drawing: the car
     * navigates itself between them, so they are the whole reason it follows
     * the camera-avoiding line rather than its own idea of the route.
     */
    steeringWaypoints: List<GeoPoint> = emptyList(),
    /**
     * Every camera near the planned route, drawn whatever the zoom. Without
     * these a camera-free route looks identical to a route with nothing around
     * it, and there is no way to see what the detour actually bought without
     * zooming in and panning along the whole line.
     */
    routeCameras: List<GeoPoint> = emptyList(),
    /** Charging sites along the route, tappable to add one as a stop. */
    chargers: List<MapCharger> = emptyList(),
    /** Called with the charger the user tapped. */
    onChargerSelected: ((MapCharger) -> Unit)? = null,
    modifier: Modifier = Modifier,
    showLocation: Boolean = true,
    cameraFetcher: (suspend (BoundingBox) -> List<MapCamera>)? = null,
    /** Long-press anywhere to route there, Google-Maps style. */
    onLongPress: ((GeoPoint) -> Unit)? = null,
    /**
     * Where the trip is going, drawn as a pin as soon as it is known.
     *
     * Separate from the route because it is known *first*: a long press or a
     * search result gives a destination immediately, and the route that reaches
     * it takes seconds to plan. Without this the map is unchanged for those
     * seconds and the press looks like it missed.
     */
    destination: GeoPoint? = null,
    /**
     * Whether more legs of this trip are still being planned.
     *
     * Drives the dashed line from the end of what is planned to the
     * destination — see [renderPending]. Passed in rather than inferred from a
     * gap in the geometry, because those are two genuinely different states: a
     * trip still being planned should say so, while a trip that finished
     * planning and simply ends where it ends should not sprout a line to
     * nowhere.
     */
    planningAhead: Boolean = false,
    /**
     * The pin the car is currently aiming at, while a drive is running.
     *
     * Non-null turns on [followDrive] — the map keeps the driver and this point
     * both in frame instead of sitting wherever it was last left.
     */
    followTo: GeoPoint? = null,
    /** The direct road onward from what is planned. See [renderPending]. */
    directAhead: List<GeoPoint> = emptyList(),
) {
    val routeLines = listOf(routePolyline) + laterLegLines
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUrl = stringResource(R.string.map_style_url)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    // When the driver last moved the map themselves. Any pan, zoom or rotate
    // suspends the automatic framing below — a map that snaps back while
    // somebody is looking ahead at their route is the §6.1 mistake in
    // miniature, overriding something they just did on purpose.
    var touchedAt by remember { mutableStateOf(0L) }
    var style by remember { mutableStateOf<Style?>(null) }
    var viewportCameras by remember { mutableStateOf<List<MapCamera>>(emptyList()) }
    var selectedCamera by remember { mutableStateOf<MapCamera?>(null) }
    // Set by the map's camera-idle listener; drives the viewport fetch below.
    var requestedBounds by remember { mutableStateOf<BoundingBox?>(null) }
    val locationActivated = remember { mutableStateOf(false) }
    // The map listeners are registered once; this keeps them pointing at the
    // current callback instead of the one captured on first composition.
    val longPress = rememberUpdatedState(onLongPress)
    // Read inside the map's click listener, which outlives this composition.
    val chargerState = rememberUpdatedState(chargers)
    val onChargerTap = rememberUpdatedState(onChargerSelected)
    // Which route we've already framed, so we fit once per route and don't
    // fight the user's panning afterward.
    val fitKey = remember { mutableStateOf<Int?>(null) }
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            val builder = if (styleUrl.isNotBlank()) Style.Builder().fromUri(styleUrl)
            else Style.Builder().fromJson(BLANK_STYLE)
            map.setStyle(builder) { loaded ->
                style = loaded
                straightenOneWayArrows(loaded, context)
                // Before anything of ours is added, so every route, camera and
                // pin layer lands on top of the labels rather than under them.
                addPlaceLabels(loaded)
                // Report the viewport whenever the user stops moving the map so
                // we can fetch the cameras now visible.
                map.addOnCameraIdleListener {
                    requestedBounds = runCatching { map.visibleBounds() }.getOrNull()
                }
                // Only *gesture*-driven movement counts as the driver taking
                // over. Using onCameraMove instead would have the follow camera
                // treat its own easing as a manual pan and switch itself off on
                // the first frame.
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        touchedAt = System.currentTimeMillis()
                    }
                }
                // Tap a charger to add it, or a camera dot to see its details.
                // Chargers are tested first: they are the smaller, deliberate
                // target, and a camera dot underneath must not steal the tap.
                map.addOnMapClickListener { latLng ->
                    val pt: PointF = runCatching { map.projection.toScreenLocation(latLng) }
                        .getOrNull() ?: return@addOnMapClickListener false

                    val chargerHit = runCatching {
                        map.queryRenderedFeatures(pt, CHARGER_LAYER)
                            .firstNotNullOfOrNull { f -> f.getNumberProperty("chargerId")?.toLong() }
                    }.getOrNull()
                    val charger = chargerHit?.let { id -> chargerState.value.firstOrNull { it.id == id } }
                    if (charger != null) {
                        onChargerTap.value?.invoke(charger)
                        return@addOnMapClickListener true
                    }

                    val hit = runCatching {
                        map.queryRenderedFeatures(pt, CAMERA_LAYER, PASSED_LAYER)
                            .firstNotNullOfOrNull { f ->
                                f.getNumberProperty("cameraId")?.toLong()
                            }
                    }.getOrNull()
                    val cam = hit?.let { id -> viewportCameras.firstOrNull { it.id == id } }
                    if (cam != null) { selectedCamera = cam; true } else false
                }
                // Long-press anywhere to route to that spot.
                map.addOnMapLongClickListener { latLng ->
                    val handler = longPress.value
                    if (handler == null) {
                        false
                    } else {
                        runCatching { GeoPoint(latLng.latitude, latLng.longitude) }
                            .getOrNull()
                            ?.let { handler(it); true }
                            ?: false
                    }
                }
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Debounced viewport camera fetch: coalesce rapid pans, cap huge viewports.
    LaunchedEffect(requestedBounds, cameraFetcher) {
        val fetcher = cameraFetcher ?: return@LaunchedEffect
        val bounds = requestedBounds ?: return@LaunchedEffect
        if (bounds.maxLat - bounds.minLat > MAX_VIEWPORT_SPAN_DEG) {
            viewportCameras = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        viewportCameras = runCatching { fetcher(bounds) }.getOrDefault(viewportCameras)
    }

    // The marching ants. Only ticks while something is actually pending, so a
    // finished plan costs nothing — this is a redraw of a style layer several
    // times a second, and the phone is often planning routes on every other
    // core while it runs.
    val pendingTail = remember(routeLines, destination, planningAhead) {
        if (!planningAhead) null else routeLines.lastOrNull { it.size >= 2 }?.lastOrNull()
    }
    // **The dash phase is deliberately not Compose state.**
    //
    // It was, and that is what made the animation jump: every frame changed a
    // `remember`ed value, which recomposed the whole `AndroidView` update block
    // — re-running renderRoute, renderChargers, renderCameras and the fit check
    // eleven times a second, each of them rebuilding GeoJSON for the entire
    // route. The line stuttered because the map was being rebuilt underneath it.
    //
    // Setting one paint property on one layer is all this ever needed, so it is
    // done straight to the style and Compose is not told about it at all.
    LaunchedEffect(pendingTail, destination, style) {
        val loaded = style ?: return@LaunchedEffect
        if (pendingTail == null || destination == null) return@LaunchedEffect
        var phase = 0
        while (true) {
            delay(PENDING_FRAME_MILLIS)
            phase = (phase + 1) % PENDING_DASHES.size
            runCatching {
                loaded.getLayerAs<LineLayer>(PENDING_LAYER)
                    ?.setProperties(PropertyFactory.lineDasharray(PENDING_DASHES[phase]))
            }
        }
    }

    // Keep the driver and the pin they are heading for both on screen.
    //
    // Reported as missing: "as someone is moving along the route, the app should
    // automatically adjust the map zoom and position to include the driver
    // marker and the next waypoint, but only if the driver hasn't touched the
    // map positioning or zoom after a certain amount of time."
    LaunchedEffect(followTo, hasLocationPermission, showLocation) {
        val target = followTo
        if (target == null || !showLocation || !hasLocationPermission) return@LaunchedEffect
        while (true) {
            val idleFor = System.currentTimeMillis() - touchedAt
            if (idleFor >= FOLLOW_RESUME_MILLIS) {
                frameDrive(mapView, target)
            }
            delay(FOLLOW_TICK_MILLIS)
        }
    }

    // Centre on the user **once**, when the map first has a fix and nothing to
    // show, so the nearby cameras load without them having to pan there first.
    //
    // Keyed on the route's length, it re-fired every time a route went away —
    // so backing out of route planning yanked the map from wherever the driver
    // had been looking straight back to their own dot. Reported as "I don't want
    // the map to go right back to my location after I hit back from the route
    // planning", and they are right: the first centring is a convenience for
    // someone who has not looked anywhere yet, and after that it is the app
    // overruling where they chose to look.
    //
    // Suppressed outright once they have moved the map themselves, which is the
    // clearest possible statement that they did not want to be here.
    var centredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(showLocation, hasLocationPermission) {
        if (centredOnce || !showLocation || !hasLocationPermission) return@LaunchedEffect
        if (routePolyline.size >= 2 || touchedAt > 0L) return@LaunchedEffect
        repeat(20) {
            if (centerOnUserLocation(mapView)) {
                centredOnce = true
                return@LaunchedEffect
            }
            delay(500)
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier) { view ->
            val loadedStyle = style ?: return@AndroidView
            if (showLocation && hasLocationPermission && !locationActivated.value) {
                if (activateLocationDot(view, loadedStyle, context)) locationActivated.value = true
            }
            renderRoute(loadedStyle, routeLines, passedCameras, steeringWaypoints, routeCameras, destination)
            renderPending(loadedStyle, pendingTail, destination, directAhead)
            renderChargers(loadedStyle, chargers)
            renderCameras(loadedStyle, viewportCameras)
            view.getMapAsync { map -> fitRouteOnce(map, routePolyline, passedCameras, fitKey, destination) }
        }

        selectedCamera?.let { cam ->
            CameraInfoCard(
                camera = cam,
                onClose = { selectedCamera = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

/**
 * Put the driver and the point they are heading for both on screen.
 *
 * Deliberately a *fit* rather than a follow-the-dot camera. Centring on the car
 * and holding a zoom is what most navigation apps do, and it answers "where am
 * I"; the question this app exists to answer is "where am I going and what is
 * between me and it", which is a question about the gap between two points. So
 * the frame is the box containing both, and it tightens by itself as the car
 * closes on the pin.
 *
 * Silent about failure on purpose: it runs on a timer, and a map that is not
 * laid out yet or has no fix yet is an ordinary state, not an error.
 */
private fun frameDrive(view: MapView, target: GeoPoint) {
    runCatching {
        view.getMapAsync { map ->
            runCatching {
                val here = map.locationComponent.lastKnownLocation ?: return@getMapAsync
                val bounds = LatLngBounds.Builder()
                    .include(LatLng(here.latitude, here.longitude))
                    .include(LatLng(target.lat, target.lon))
                    .build()
                val pad = (minOf(view.width, view.height) * FOLLOW_PADDING_FRACTION).toInt()
                    .coerceAtLeast(1)
                val update = CameraUpdateFactory.newLatLngBounds(bounds, pad, pad, pad, pad)
                // Cap the zoom rather than the padding: two points a hundred
                // metres apart would otherwise fill the screen with one junction
                // and no context at all.
                val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad))
                if (camera != null && camera.zoom > FOLLOW_MAX_ZOOM) {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(camera.target!!, FOLLOW_MAX_ZOOM),
                        FOLLOW_TICK_MILLIS.toInt() / 3,
                    )
                } else {
                    map.easeCamera(update, FOLLOW_TICK_MILLIS.toInt() / 3)
                }
            }
        }
    }
}

/** Visible map bounds as our [BoundingBox]; may throw before the map is laid out. */
private fun MapLibreMap.visibleBounds(): BoundingBox {
    val b = projection.visibleRegion.latLngBounds
    val ne = b.northEast
    val sw = b.southWest
    return BoundingBox(
        minLat = sw.latitude,
        minLon = sw.longitude,
        maxLat = ne.latitude,
        maxLon = ne.longitude,
    )
}

@SuppressLint("MissingPermission")
private fun activateLocationDot(view: MapView, style: Style, context: Context): Boolean =
    runCatching {
        view.getMapAsync { map ->
            runCatching {
                val options = LocationComponentOptions.builder(context)
                    .pulseEnabled(true)
                    .pulseColor(Color.parseColor("#1f6feb"))
                    .foregroundTintColor(Color.parseColor("#1f6feb"))
                    .accuracyColor(Color.parseColor("#1f6feb"))
                    .build()
                val activation = LocationComponentActivationOptions
                    .builder(context, style)
                    .locationComponentOptions(options)
                    .useDefaultLocationEngine(true)
                    .build()
                map.locationComponent.apply {
                    activateLocationComponent(activation)
                    isLocationComponentEnabled = true
                    cameraMode = CameraMode.NONE
                    renderMode = RenderMode.NORMAL
                }
            }
        }
        true
    }.getOrDefault(false)

@SuppressLint("MissingPermission")
private fun centerOnUserLocation(view: MapView): Boolean {
    var moved = false
    runCatching {
        view.getMapAsync { map ->
            val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull()
            if (loc != null) {
                runCatching {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.0),
                        600,
                    )
                }
                moved = true
            }
        }
    }
    return moved
}

/**
 * The dashed, semitransparent line from the end of what has been planned to
 * where the trip is actually going.
 *
 * A long trip is cut into legs and only the first is ready when the driver
 * looks at the chooser (see `LegSplitter`); the rest arrive over the following
 * seconds. Until they do, the drawn route simply *stops* somewhere in open
 * country, which reads as the route being wrong rather than as the app still
 * working. This says the difference out loud: a solid line for road that has
 * been planned, a marching dashed one for the part that has not.
 *
 * Deliberately drawn as a straight line rather than the direct road. It is not
 * a route and must not be mistakable for one — it is a statement that something
 * is still coming, and the movement is what carries that.
 *
 * Cleared by setting the source to an empty collection rather than by skipping
 * the update, which is the same trap that once left a cancelled route on the
 * map forever: a source that is only ever *set* keeps its last value.
 */
private fun renderPending(
    style: Style,
    tail: GeoPoint?,
    destination: GeoPoint?,
    /**
     * The direct road onward from the end of what is planned, when it is known.
     *
     * The spine is already computed to choose the leg boundary, so following it
     * costs nothing and says far more than a straight line: the pending stretch
     * runs along roads that exist, and each leg that lands replaces a piece of
     * it with the camera-avoiding version. Empty falls back to the straight
     * line, which is all there is on a trip whose spine could not be routed.
     */
    directAhead: List<GeoPoint>,
) {
    val show = tail != null && destination != null &&
        // Nothing to draw once the plan has arrived where it is going. The
        // threshold is generous: a leg boundary lands on a road near the
        // destination, not on top of it.
        app.shunt.solver.geo.haversineMeters(tail, destination) > PENDING_MIN_METERS
    val features = if (!show) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        // From the end of what is planned, along the direct road where it is
        // known, and on to the destination.
        val ahead = directAhead.filter { onwardOf(tail!!, it, destination!!) }
        val line = listOf(tail!!) + ahead + destination!!
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) }),
                ),
            ),
        )
    }

    val source = style.getSourceAs<GeoJsonSource>(PENDING_SOURCE)
    if (source != null) {
        source.setGeoJson(features)
    } else {
        style.addSource(GeoJsonSource(PENDING_SOURCE, features))
        style.addLayer(
            LineLayer(PENDING_LAYER, PENDING_SOURCE).withProperties(
                PropertyFactory.lineColor("#8ab4f8"),
                PropertyFactory.lineWidth(3f),
                // Semitransparent on purpose: this is the one line on the map
                // that is not a road, and it should never read as solidly as
                // the route it is waiting for.
                PropertyFactory.lineOpacity(0.45f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
    }
}

/**
 * Whether a point on the direct road still lies ahead of [tail].
 *
 * The spine covers the whole trip, and the part behind the planned route would
 * draw the pending line backwards through road already covered. Compared by
 * distance to the destination, which is coarse and is all this needs: it only
 * has to drop the points already passed.
 */
private fun onwardOf(tail: GeoPoint, p: GeoPoint, destination: GeoPoint): Boolean =
    app.shunt.solver.geo.haversineMeters(p, destination) <
        app.shunt.solver.geo.haversineMeters(tail, destination)

private fun renderRoute(
    style: Style,
    /** Each leg's line separately; see below for why they are never merged. */
    lines: List<List<GeoPoint>>,
    passed: List<GeoPoint>,
    waypoints: List<GeoPoint> = emptyList(),
    nearby: List<GeoPoint> = emptyList(),
    destination: GeoPoint? = null,
) {
    // Cameras near the route but not on it — what the detour is avoiding. Drawn
    // first so the passed ones and the pins sit on top.
    val nearbyFeatures = FeatureCollection.fromFeatures(
        nearby.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
    )
    val nearbySource = style.getSourceAs<GeoJsonSource>(NEARBY_SOURCE)
    if (nearbySource != null) {
        nearbySource.setGeoJson(nearbyFeatures)
    } else {
        style.addSource(GeoJsonSource(NEARBY_SOURCE, nearbyFeatures))
        style.addLayer(
            CircleLayer(NEARBY_LAYER, NEARBY_SOURCE).withProperties(
                PropertyFactory.circleColor("#ffb020"),
                PropertyFactory.circleRadius(4f),
                PropertyFactory.circleOpacity(0.85f),
            ),
        )
    }

    // **One feature per leg, never one line through all of them.** Joining the
    // legs of a long trip into a single LineString draws a straight segment
    // across country wherever two of them do not share an endpoint exactly —
    // which is what put a ruled line from Milwaukee to Chicago across a planned
    // route. Separate features cannot do that whatever the legs contain.
    // **Built and set unconditionally, including when it is empty.** Guarding
    // this on "are there any lines" is how a cancelled trip kept its route drawn
    // for the rest of the session: with nothing to draw the block was skipped
    // entirely, so the source kept whatever was last put in it and the line
    // stayed on the map with no state behind it. Clearing is a state too.
    run {
        val line = FeatureCollection.fromFeatures(
            lines.filter { it.size >= 2 }.map { leg ->
                Feature.fromGeometry(
                    LineString.fromLngLats(leg.map { Point.fromLngLat(it.lon, it.lat) }),
                )
            },
        )
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        if (source != null) {
            source.setGeoJson(line)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, line))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#1f6feb"),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
    }

    // Where the trip is going. Drawn first and independently of the route, so
    // it appears the instant a destination is picked.
    val destinationFeatures = FeatureCollection.fromFeatures(
        listOfNotNull(destination?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }),
    )
    val destinationSource = style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE)
    if (destinationSource != null) {
        destinationSource.setGeoJson(destinationFeatures)
    } else {
        style.addSource(GeoJsonSource(DESTINATION_SOURCE, destinationFeatures))
        style.addLayer(
            CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                PropertyFactory.circleColor("#1f6feb"),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    // The pins the car gets steered through, under the camera dots so an
    // unavoidable camera is never hidden behind one.
    val waypointFeatures = FeatureCollection.fromFeatures(
        waypoints.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
    )
    val waypointSource = style.getSourceAs<GeoJsonSource>(WAYPOINT_SOURCE)
    if (waypointSource != null) {
        waypointSource.setGeoJson(waypointFeatures)
    } else {
        style.addSource(GeoJsonSource(WAYPOINT_SOURCE, waypointFeatures))
        style.addLayer(
            CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
                PropertyFactory.circleColor("#ffffff"),
                PropertyFactory.circleRadius(4.5f),
                PropertyFactory.circleStrokeColor("#1f6feb"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
    }

    // Passed cameras: the unavoidable ALPRs on the chosen route, in alarm red.
    val passedFeatures = FeatureCollection.fromFeatures(
        passed.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
    )
    val passedSource = style.getSourceAs<GeoJsonSource>(PASSED_SOURCE)
    if (passedSource != null) {
        passedSource.setGeoJson(passedFeatures)
    } else {
        style.addSource(GeoJsonSource(PASSED_SOURCE, passedFeatures))
        style.addLayer(
            CircleLayer(PASSED_LAYER, PASSED_SOURCE).withProperties(
                PropertyFactory.circleColor("#ff5a4d"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }
}

/** Draw the charging sites on offer, as a target big enough to hit while driving. */
/**
 * Turn the basemap's one-way arrows to point along the road.
 *
 * They point a quarter-turn off, and have since the basemap was adopted. The
 * cause is in the OpenFreeMap style rather than in Shunt: its `oneway` sprite is
 * an arrow drawn pointing **up**, while MapLibre's `symbol-placement: line`
 * aligns a symbol's **+X (right)** axis with the direction of the line. So the
 * arrow comes out 90° anticlockwise of the road.
 *
 * Measured rather than guessed, by rendering that same sprite and layout over
 * lines of known bearing:
 *
 * | line runs | arrow points |
 * |---|---|
 * | west → east | north |
 * | east → west | south |
 * | southwest → northeast | northwest |
 *
 * Consistently 90° anticlockwise, and note the first two are opposites — the
 * *direction* is honoured, it is the axis that is wrong. Adding 90° to each
 * layer's `icon-rotate` lands them on the road.
 *
 * **Only applied when the values are the ones known to be wrong** (0 for the
 * forward layer, 180 for the reversed one). The style is fetched at run time
 * from a server this project does not control, so if it is ever corrected
 * upstream this patch would otherwise turn correct arrows into wrong ones —
 * which is a worse bug than the one being fixed, because nobody would be
 * looking for it.
 */
/**
 * Name the places on the map — shops, restaurants, parks, schools — the way
 * OpenStreetMap and Google do.
 *
 * The basemap does not. OpenFreeMap's dark style descends from Dark Matter,
 * which is a *backdrop* style: it draws city, town, street and water names and
 * deliberately nothing else, because it was designed to sit under somebody
 * else's data. On a navigation map that is a real gap — a driver looking at a
 * detour has no way to tell what is around them, and no landmark to recognise
 * the turn by.
 *
 * **The names were already being downloaded.** The tiles Shunt fetches carry a
 * `poi` source-layer from z11 and a `park` one from z4, both with `name` and
 * `rank`; the style simply has no layer drawing them. So this costs no extra
 * request, no new host, and nothing that could need an account — it is the same
 * keyless OpenFreeMap tile, rendered more fully.
 *
 * Everything here is wrapped in `runCatching` for the same reason
 * [straightenOneWayArrows] is: the style is fetched at run time from a server
 * this project does not control, and a basemap that changed its source id must
 * degrade to a map without labels rather than to no map at all.
 */
private fun addPlaceLabels(style: Style) {
    if (style.getSource(BASEMAP_SOURCE) == null) return

    // Bright enough to read against the dark basemap, with a dark halo so a
    // label crossing a road or a route line stays legible. Deliberately dimmer
    // than the route and the camera dots: this is context, and it must never
    // compete with the two things the driver is actually looking for.
    fun label(
        id: String,
        sourceLayer: String,
        from: Float,
        size: Float,
        colour: String,
    ): SymbolLayer = SymbolLayer(id, BASEMAP_SOURCE).apply {
        setSourceLayer(sourceLayer)
        minZoom = from
        withProperties(
            // `name_en` where the tile has it, else the local name — the same
            // preference the style's own label layers express.
            PropertyFactory.textField(Expression.coalesce(Expression.get("name_en"), Expression.get("name"))),
            // The one font stack the style ships glyphs for. Naming anything
            // else renders nothing at all, silently.
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(size),
            PropertyFactory.textColor(colour),
            PropertyFactory.textHaloColor("rgba(0,0,0,0.85)"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textMaxWidth(8f),
            // Anchored above the point with the label pushed down, so the dot
            // sits where the place is and the name hangs beneath it.
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.55f)),
            // Left at the default (false), which is what makes MapLibre thin
            // the labels out as they collide. A dense high street would
            // otherwise draw every name on top of every other.
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(true),
            // Lower rank is more significant, and MapLibre keeps the lower sort
            // key when two labels collide — so the ranking survives the
            // thinning rather than being decided by draw order.
            PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("rank"))),
        )
    }

    runCatching {
        val parks = label(PARK_LAYER, PARK_SOURCE_LAYER, PARK_MIN_ZOOM, 11f, "rgb(150,180,150)")
        style.addLayer(parks)
    }

    // The dot itself, under the POI names. Small and dim by design: it marks a
    // position, and it shares the map with camera dots that a driver is
    // actually looking for. Anything more prominent would compete with them.
    runCatching {
        val dots = CircleLayer(POI_DOT_LAYER, BASEMAP_SOURCE).apply {
            setSourceLayer(POI_SOURCE_LAYER)
            minZoom = POI_MIN_ZOOM
            withProperties(
                PropertyFactory.circleRadius(2.2f),
                PropertyFactory.circleColor("rgb(150,150,145)"),
                PropertyFactory.circleOpacity(0.8f),
                // A dark ring so a dot on a pale road is still a dot.
                PropertyFactory.circleStrokeWidth(0.8f),
                PropertyFactory.circleStrokeColor("rgba(0,0,0,0.8)"),
            )
        }
        dots.setFilter(poiVisible())
        style.addLayer(dots)
    }

    runCatching {
        val poi = label(POI_LAYER, POI_SOURCE_LAYER, POI_MIN_ZOOM, 11f, "rgb(190,190,185)")
        // Hold the least significant back for two more zoom levels. Without
        // this, zooming to a town centre paints a solid block of text over the
        // very roads the route is drawn on.
        poi.setFilter(poiVisible())
        style.addLayer(poi)
    }
}

/**
 * Which POIs are worth drawing at all — shared by the dots and the names so the
 * two can never disagree about what is on the map.
 *
 * Below two zoom levels above [POI_MIN_ZOOM] only the significant ones show,
 * because a town centre at z14 is otherwise a solid block of text over the very
 * roads the route runs on.
 */
private fun poiVisible(): Expression = Expression.any(
    Expression.gte(Expression.zoom(), Expression.literal(POI_MIN_ZOOM + 2f)),
    Expression.lt(Expression.toNumber(Expression.get("rank")), Expression.literal(POI_RANK_LIMIT)),
)

private fun straightenOneWayArrows(style: Style, context: Context) {
    // Our own arrow, drawn pointing +X, replaces the basemap's.
    //
    // Two things wrong with theirs, and swapping the image fixes both at once.
    // It points up, which is the quarter-turn above. And it is 21 px of mostly
    // *tail* — fine on a straight road, poor on anything that curves, because
    // MapLibre rotates each symbol to the local direction but the symbol itself
    // stays a straight line. On a roundabout that is a long stroke cutting the
    // chord of the circle rather than an arrow following it, which is what
    // made those look wrong even once the rotation was right.
    //
    // A compact head has no tail to disagree with the curve, and closer spacing
    // puts several around a roundabout so it reads as circulation rather than
    // as one stray mark.
    val icon = oneWayArrowBitmap(context)
    runCatching { style.addImage(ONE_WAY_ICON, icon) }

    fun apply(id: String, rotate: Float) {
        val layer = runCatching { style.getLayerAs<SymbolLayer>(id) }.getOrNull() ?: return
        runCatching {
            layer.setProperties(
                PropertyFactory.iconImage(ONE_WAY_ICON),
                // Absolute, not relative: we author the icon along +X, so these
                // are the whole truth about its orientation and stay correct
                // whatever the upstream style does with its own sprite later.
                PropertyFactory.iconRotate(rotate),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.symbolSpacing(ONE_WAY_SPACING_DP),
            )
        }
    }
    apply(ONE_WAY_LAYER, 0f)
    apply(ONE_WAY_REVERSED_LAYER, 180f)
}

/**
 * A small solid arrowhead pointing **+X (right)**, which is the axis MapLibre
 * aligns with the direction of a line.
 *
 * Drawn at the display's density and tagged with it, so MapLibre scales it the
 * way it scales the style's own sprite sheet and it comes out the same size on
 * screen on every device.
 */
private fun oneWayArrowBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (ONE_WAY_ICON_DP * density).toInt().coerceAtLeast(8)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.density = context.resources.displayMetrics.densityDpi
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // A triangle spanning most of the width, centred vertically: tip at the
    // right, base at the left.
    val inset = size * 0.18f
    val path = Path().apply {
        moveTo(size - inset, size / 2f)
        lineTo(inset, inset)
        lineTo(inset, size - inset)
        close()
    }
    canvas.drawPath(path, paint)
    return bitmap
}

private fun renderChargers(style: Style, chargers: List<MapCharger>) {
    val features = FeatureCollection.fromFeatures(
        chargers.map { charger ->
            Feature.fromGeometry(Point.fromLngLat(charger.lon, charger.lat)).apply {
                addNumberProperty("chargerId", charger.id)
            }
        },
    )
    val source = style.getSourceAs<GeoJsonSource>(CHARGER_SOURCE)
    if (source != null) {
        source.setGeoJson(features)
    } else {
        style.addSource(GeoJsonSource(CHARGER_SOURCE, features))
        style.addLayer(
            CircleLayer(CHARGER_LAYER, CHARGER_SOURCE).withProperties(
                PropertyFactory.circleColor("#35d07f"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#0b3d24"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }
}

/** Draw every known camera in view: a muted dot plus a facing cone where known. */
private fun renderCameras(style: Style, cameras: List<MapCamera>) {
    val dots = FeatureCollection.fromFeatures(
        cameras.map { cam ->
            Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply {
                addNumberProperty("cameraId", cam.id)
            }
        },
    )
    // The watched area: a 180° field-of-view wedge where the facing is known,
    // a full circle where it isn't — matching what routing actually avoids.
    val cones = FeatureCollection.fromFeatures(cameras.map { visionFeature(it) })

    val coneSource = style.getSourceAs<GeoJsonSource>(CONE_SOURCE)
    if (coneSource != null) {
        coneSource.setGeoJson(cones)
    } else {
        style.addSource(GeoJsonSource(CONE_SOURCE, cones))
        style.addLayer(
            FillLayer(CONE_LAYER, CONE_SOURCE).withProperties(
                PropertyFactory.fillColor("#b3261e"),
                PropertyFactory.fillOpacity(0.22f),
            ),
        )
    }

    val dotSource = style.getSourceAs<GeoJsonSource>(CAMERA_SOURCE)
    if (dotSource != null) {
        dotSource.setGeoJson(dots)
    } else {
        style.addSource(GeoJsonSource(CAMERA_SOURCE, dots))
        style.addLayer(
            CircleLayer(CAMERA_LAYER, CAMERA_SOURCE).withProperties(
                PropertyFactory.circleColor("#d68a2e"),
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleStrokeColor("#161826"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
    }
}

/**
 * The camera's watched area, matching what routing avoids: a 180° wedge around
 * a known facing, or a full circle when the facing is unknown.
 */
private fun visionFeature(cam: MapCamera): Feature {
    val apex = GeoPoint(cam.lat, cam.lon)
    val ring = mutableListOf<Point>()
    val direction = cam.directionDegrees
    if (direction != null) {
        ring += Point.fromLngLat(apex.lon, apex.lat) // wedge apex at the camera
        val steps = 12
        for (i in 0..steps) {
            val bearing = direction - CameraVision.FOV_HALF_ANGLE +
                (2 * CameraVision.FOV_HALF_ANGLE) * i / steps
            val edge = destinationPoint(apex, bearing, CameraVision.DIRECTIONAL_RANGE_M * cam.rangeScale)
            ring += Point.fromLngLat(edge.lon, edge.lat)
        }
        ring += Point.fromLngLat(apex.lon, apex.lat) // close back to the apex
    } else {
        val steps = 24
        for (i in 0..steps) {
            val edge = destinationPoint(apex, 360.0 * i / steps, CameraVision.OMNI_RANGE_M * cam.rangeScale)
            ring += Point.fromLngLat(edge.lon, edge.lat)
        }
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

/** Fit the map to the route the first time a new route appears; never fight panning after. */
private fun fitRouteOnce(
    map: MapLibreMap,
    polyline: List<GeoPoint>,
    cameras: List<GeoPoint>,
    fitKey: androidx.compose.runtime.MutableState<Int?>,
    destination: GeoPoint? = null,
) {
    // With no route yet there is still somewhere to look: a destination just
    // picked may be off-screen, and framing it is what makes a long press on a
    // far part of the map feel like it did something.
    if (polyline.size < 2) {
        // Cancelled back to an empty map: forget what was framed, so planning
        // the *same* trip again still moves the camera to it rather than
        // deciding it is already showing.
        val target = destination ?: run { fitKey.value = null; return }
        val key = target.hashCode()
        if (key == fitKey.value) return
        fitKey.value = key
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), LONE_PIN_ZOOM))
        return
    }
    val key = polyline.hashCode()
    if (key == fitKey.value) return
    val points = (polyline + cameras + listOfNotNull(destination)).map { LatLng(it.lat, it.lon) }
    val bounds = runCatching {
        LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    }.getOrNull() ?: return
    runCatching { map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600) }
    fitKey.value = key
}

@Composable
private fun CameraInfoCard(camera: MapCamera, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            // Title on the left, close in the top-right corner.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    camera.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            camera.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                camera.directionDegrees?.let { "Faces ${cardinal(it)} (${it.toInt()}°)" }
                    ?: "Facing not recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Nearest 8-point compass label for a bearing in degrees. */
private fun cardinal(deg: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8
    return names[idx]
}
