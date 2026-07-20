package app.shunt.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
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
)

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

// Facing-cone geometry.
private const val CONE_METERS = 45.0
private const val CONE_HALF_ANGLE = 28.0

/** Above this viewport span (~44 km) we don't fetch cameras — too many, too zoomed out. */
private const val MAX_VIEWPORT_SPAN_DEG = 0.4

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
    passedCameras: List<GeoPoint>,
    modifier: Modifier = Modifier,
    showLocation: Boolean = true,
    cameraFetcher: (suspend (BoundingBox) -> List<MapCamera>)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUrl = stringResource(R.string.map_style_url)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var style by remember { mutableStateOf<Style?>(null) }
    var viewportCameras by remember { mutableStateOf<List<MapCamera>>(emptyList()) }
    var selectedCamera by remember { mutableStateOf<MapCamera?>(null) }
    // Set by the map's camera-idle listener; drives the viewport fetch below.
    var requestedBounds by remember { mutableStateOf<BoundingBox?>(null) }
    val locationActivated = remember { mutableStateOf(false) }
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
                // Report the viewport whenever the user stops moving the map so
                // we can fetch the cameras now visible.
                map.addOnCameraIdleListener {
                    requestedBounds = runCatching { map.visibleBounds() }.getOrNull()
                }
                // Tap a camera dot to see its details.
                map.addOnMapClickListener { latLng ->
                    val hit = runCatching {
                        val pt: PointF = map.projection.toScreenLocation(latLng)
                        map.queryRenderedFeatures(pt, CAMERA_LAYER, PASSED_LAYER)
                            .firstNotNullOfOrNull { f ->
                                f.getNumberProperty("cameraId")?.toLong()
                            }
                    }.getOrNull()
                    val cam = hit?.let { id -> viewportCameras.firstOrNull { it.id == id } }
                    if (cam != null) { selectedCamera = cam; true } else false
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

    // Once we have a location fix and no route is shown, center on the user so
    // the nearby cameras load without them having to pan there first.
    LaunchedEffect(showLocation, hasLocationPermission, routePolyline.size) {
        if (!showLocation || !hasLocationPermission || routePolyline.size >= 2) return@LaunchedEffect
        repeat(20) {
            if (centerOnUserLocation(mapView)) return@LaunchedEffect
            delay(500)
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier) { view ->
            val loadedStyle = style ?: return@AndroidView
            if (showLocation && hasLocationPermission && !locationActivated.value) {
                if (activateLocationDot(view, loadedStyle, context)) locationActivated.value = true
            }
            renderRoute(loadedStyle, routePolyline, passedCameras)
            renderCameras(loadedStyle, viewportCameras)
            view.getMapAsync { map -> fitRouteOnce(map, routePolyline, passedCameras, fitKey) }
        }

        selectedCamera?.let { cam ->
            CameraInfoCard(
                camera = cam,
                onClose = { selectedCamera = null },
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
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

private fun renderRoute(style: Style, polyline: List<GeoPoint>, passed: List<GeoPoint>) {
    if (polyline.size >= 2) {
        val line = Feature.fromGeometry(
            LineString.fromLngLats(polyline.map { Point.fromLngLat(it.lon, it.lat) }),
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

/** Draw every known camera in view: a muted dot plus a facing cone where known. */
private fun renderCameras(style: Style, cameras: List<MapCamera>) {
    val dots = FeatureCollection.fromFeatures(
        cameras.map { cam ->
            Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply {
                addNumberProperty("cameraId", cam.id)
            }
        },
    )
    val cones = FeatureCollection.fromFeatures(
        cameras.mapNotNull { cam -> cam.directionDegrees?.let { coneFeature(cam, it) } },
    )

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

/** A sector polygon fanning out from the camera along [directionDeg]. */
private fun coneFeature(cam: MapCamera, directionDeg: Double): Feature {
    val apex = GeoPoint(cam.lat, cam.lon)
    val ring = mutableListOf(Point.fromLngLat(apex.lon, apex.lat))
    val steps = 6
    for (i in 0..steps) {
        val bearing = directionDeg - CONE_HALF_ANGLE + (2 * CONE_HALF_ANGLE) * i / steps
        val edge = destinationPoint(apex, bearing, CONE_METERS)
        ring.add(Point.fromLngLat(edge.lon, edge.lat))
    }
    ring.add(Point.fromLngLat(apex.lon, apex.lat)) // close the ring
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

/** Fit the map to the route the first time a new route appears; never fight panning after. */
private fun fitRouteOnce(
    map: MapLibreMap,
    polyline: List<GeoPoint>,
    cameras: List<GeoPoint>,
    fitKey: androidx.compose.runtime.MutableState<Int?>,
) {
    if (polyline.size < 2) return
    val key = polyline.hashCode()
    if (key == fitKey.value) return
    val points = (polyline + cameras).map { LatLng(it.lat, it.lon) }
    val bounds = runCatching {
        LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    }.getOrNull() ?: return
    runCatching { map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600) }
    fitKey.value = key
}

@Composable
private fun CameraInfoCard(camera: MapCamera, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.widthIn(max = 320.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    camera.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.padding(0.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            camera.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            camera.directionDegrees?.let {
                Text(
                    "Faces ${cardinal(it)} (${it.toInt()}°)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Nearest 8-point compass label for a bearing in degrees. */
private fun cardinal(deg: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8
    return names[idx]
}
