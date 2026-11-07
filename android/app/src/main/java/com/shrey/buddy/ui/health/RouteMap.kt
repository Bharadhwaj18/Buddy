package com.shrey.buddy.ui.health

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shrey.buddy.ui.theme.BuddyColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A stretch of route drawn in one colour (e.g. by pace). Points are (lat, lng). */
data class RouteSegment(val points: List<Pair<Double, Double>>, val color: androidx.compose.ui.graphics.Color = BuddyColors.Fitness)

/** OpenFreeMap: free vector tiles, no key. Attribution is shown by the map. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/**
 * Real map (MapLibre + OpenFreeMap) with one or more routes on it.
 *  - [follow]: keep the camera on this point (live run); otherwise the camera fits all routes.
 *  - [faint]: draw thin and translucent (the all-runs heatmap).
 */
@Composable
fun RouteMap(
    segments: List<RouteSegment>,
    modifier: Modifier = Modifier,
    follow: Pair<Double, Double>? = null,
    showEnds: Boolean = true,
    faint: Boolean = false,
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fitted by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // Catch up if we were added after the screen started.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isAttributionEnabled = true
                isLogoEnabled = false
                isRotateGesturesEnabled = interactive
                isTiltGesturesEnabled = false
                isScrollGesturesEnabled = interactive
                isZoomGesturesEnabled = interactive
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource(ROUTE_SRC))
                s.addSource(GeoJsonSource(ENDS_SRC))
                s.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineWidth(if (faint) 2.5f else 5f),
                        PropertyFactory.lineOpacity(if (faint) 0.35f else 1f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                s.addLayer(
                    CircleLayer(ENDS_LAYER, ENDS_SRC).withProperties(
                        PropertyFactory.circleRadius(6.5f),
                        PropertyFactory.circleColor(Expression.get("color")),
                        PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style = s
            }
        }
    }

    // Push route data whenever it (or the style) changes.
    LaunchedEffect(style, segments, follow) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val lines = segments.filter { it.points.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.points.map { (lat, lng) -> Point.fromLngLat(lng, lat) })).apply {
                addStringProperty("color", hex(seg.color))
            }
        }
        s.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(FeatureCollection.fromFeatures(lines))

        val all = segments.flatMap { it.points }
        val ends = if (showEnds && all.isNotEmpty()) listOfNotNull(
            Feature.fromGeometry(Point.fromLngLat(all.first().second, all.first().first)).apply { addStringProperty("color", hex(BuddyColors.Lime)) },
            if (follow == null && all.size > 1) Feature.fromGeometry(Point.fromLngLat(all.last().second, all.last().first)).apply { addStringProperty("color", hex(BuddyColors.Red)) } else null,
            follow?.let { (lat, lng) -> Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply { addStringProperty("color", hex(BuddyColors.Tasks)) } },
        ) else emptyList()
        s.getSourceAs<GeoJsonSource>(ENDS_SRC)?.setGeoJson(FeatureCollection.fromFeatures(ends))

        when {
            follow != null -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(follow.first, follow.second)).zoom(maxOf(m.cameraPosition.zoom, 15.5)).build(),
                ),
                600,
            )
            all.size >= 2 && !fitted -> {
                val bounds = LatLngBounds.Builder().apply { all.forEach { (lat, lng) -> include(LatLng(lat, lng)) } }.build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                fitted = true
            }
            all.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].first, all[0].second), 15.0))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

private const val ROUTE_SRC = "route-src"
private const val ROUTE_LAYER = "route-line"
private const val ENDS_SRC = "ends-src"
private const val ENDS_LAYER = "ends-dots"
