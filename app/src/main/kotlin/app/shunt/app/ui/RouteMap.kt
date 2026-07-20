package app.shunt.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shunt.R
import app.shunt.core.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Plain dark background style used when no basemap URL is configured or it fails to load. */
private const val BLANK_STYLE =
    """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#161826"}}]}"""

private const val ROUTE_SOURCE = "route"
private const val ROUTE_LAYER = "route-line"
private const val CAMERA_SOURCE = "cameras"
private const val CAMERA_LAYER = "camera-dots"

/**
 * MapLibre map (never the Google Maps SDK) showing the chosen route and any
 * cameras it passes. Cameras are drawn in alarm red — for a minimum-exposure
 * route these are the unavoidable ALPRs the user is accepting. Basemap tiles
 * come from Protomaps when configured (see R.string.map_style_url).
 */
@Composable
fun RouteMap(
    routePolyline: List<GeoPoint>,
    passedCameras: List<GeoPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUrl = stringResource(R.string.map_style_url)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val style = remember { mutableStateOf<Style?>(null) }

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
            map.setStyle(builder) { loaded -> style.value = loaded }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { view ->
        val loadedStyle = style.value ?: return@AndroidView
        renderRoute(loadedStyle, routePolyline, passedCameras)
        view.getMapAsync { map -> fitBounds(map, routePolyline, passedCameras) }
    }
}

private fun renderRoute(style: Style, polyline: List<GeoPoint>, cameras: List<GeoPoint>) {
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

    val cameraFeatures = FeatureCollection.fromFeatures(
        cameras.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
    )
    val cameraSource = style.getSourceAs<GeoJsonSource>(CAMERA_SOURCE)
    if (cameraSource != null) {
        cameraSource.setGeoJson(cameraFeatures)
    } else {
        style.addSource(GeoJsonSource(CAMERA_SOURCE, cameraFeatures))
        style.addLayer(
            CircleLayer(CAMERA_LAYER, CAMERA_SOURCE).withProperties(
                PropertyFactory.circleColor("#b3261e"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }
}

private fun fitBounds(
    map: org.maplibre.android.maps.MapLibreMap,
    polyline: List<GeoPoint>,
    cameras: List<GeoPoint>,
) {
    val points = (polyline + cameras).map { LatLng(it.lat, it.lon) }
    if (points.size < 2) return
    val bounds = runCatching {
        LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    }.getOrNull() ?: return
    runCatching { map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600) }
}
