package com.alexdyakin.lexicon.ui.pokemon.map

import android.content.Context
import android.preference.PreferenceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Holds the live [MapView] and a tick that changes whenever the map moves.
 *
 * Entities are drawn as Compose overlays rather than osmdroid `Marker`s, so that
 * sprites load through Coil, taps are ordinary `clickable`s and the markers can be
 * themed and animated like the rest of the app. The cost of that choice is this
 * handle: Compose has to be told to recompose when the map is panned or zoomed, which
 * [moveTick] does.
 */
class OsmMapHandle internal constructor(internal val view: MapView) {
    internal var moveTick by mutableIntStateOf(0)

    /**
     * True once the user has actually touched the map.
     *
     * Deliberately driven by a touch listener rather than by [moveTick]: scroll and
     * zoom events also fire for the first layout and for programmatic centring, so
     * treating those as "the user panned away" switched follow-mode off before the map
     * had ever centred on the player.
     */
    var userMoved by mutableStateOf(false)
        internal set

    fun clearUserMoved() {
        userMoved = false
    }

    /** Screen position of a coordinate, or null before the map has been laid out. */
    fun toScreen(lat: Double, lng: Double): Offset? {
        @Suppress("UNUSED_EXPRESSION") moveTick // read so callers recompose on movement
        if (view.width == 0 || view.height == 0) return null
        val point = view.projection.toPixels(GeoPoint(lat, lng), null)
        return Offset(point.x.toFloat(), point.y.toFloat())
    }

    /** Coordinate under a screen position, for placing things by tapping. */
    fun toGeo(offset: Offset): Pair<Double, Double>? {
        if (view.width == 0 || view.height == 0) return null
        val geo = view.projection.fromPixels(offset.x.toInt(), offset.y.toInt())
        return geo.latitude to geo.longitude
    }

    /**
     * How many screen pixels a ground distance covers at [lat], for drawing the range
     * rings at their true size. Asked of the projection rather than derived from a
     * metres-per-pixel constant, because the scale changes with latitude and the ring
     * would be visibly wrong away from the equator.
     */
    fun pixelsFor(metres: Double, lat: Double): Float {
        @Suppress("UNUSED_EXPRESSION") moveTick
        if (view.width == 0 || view.height == 0) return 0f
        return view.projection.metersToPixels(metres.toFloat(), lat, view.projection.zoomLevel)
    }

    fun centreOn(lat: Double, lng: Double) {
        view.controller.setCenter(GeoPoint(lat, lng))
    }
}

/**
 * OpenStreetMap tiles — the same source the website's Leaflet map uses, so the two
 * look alike and neither needs an API key.
 */
@Composable
fun OsmMap(
    handle: (OsmMapHandle) -> Unit,
    modifier: Modifier = Modifier,
    initialZoom: Double = MapWorld.DEFAULT_ZOOM,
) {
    val context = LocalContext.current
    rememberOsmConfiguration(context)

    val lifecycleOwner = LocalLifecycleOwner.current
    var created by remember { mutableStateOf<OsmMapHandle?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // The built-in +/- buttons overlap the app's own controls, and pinch
                // zoom covers the same ground.
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER,
                )
                controller.setZoom(initialZoom)
                val wrapped = OsmMapHandle(this)
                addMapListener(
                    object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            wrapped.moveTick++
                            return false
                        }

                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            wrapped.moveTick++
                            return false
                        }
                    },
                )
                // Also tick once the view has real dimensions, otherwise overlays
                // computed before the first layout stay stuck at their null position.
                addOnFirstLayoutListener { _, _, _, _, _ -> wrapped.moveTick++ }
                // Returns false so the map still handles the gesture itself; this only
                // observes that a gesture happened.
                setOnTouchListener { _, _ ->
                    wrapped.userMoved = true
                    false
                }
                created = wrapped
                handle(wrapped)
            }
        },
    )

    // osmdroid keeps its own tile threads and needs the pause/resume pair; without it
    // the map keeps downloading tiles after the screen is gone.
    DisposableEffect(lifecycleOwner, created) {
        val view = created?.view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // An observer is only told about events from here on, and this composable is
        // mounted long after the activity resumed — so without this the MapView never
        // receives onResume, never starts its tile thread, and the map stays blank with
        // nothing logged anywhere to say why.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            view?.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // onPause stops the tile threads, which is the part that matters.
            //
            // onDetach() is deliberately NOT called, even though osmdroid's own docs
            // suggest it: it closes the shared SQL tile writer, and a MapView created
            // afterwards then fails every tile with "mWriter being null (map shutdown?)"
            // and renders blank. Leaving the view to be collected costs nothing here,
            // because the whole screen goes with it.
            view?.onPause()
        }
    }
}

/**
 * osmdroid's one-time global setup.
 *
 * The user agent is not optional: OpenStreetMap's tile servers reject the library's
 * default and the map comes up blank with no error anywhere in the UI. The cache is
 * pointed at the app's own directory so the map needs no storage permission, which
 * osmdroid would otherwise ask for on older devices.
 */
@Composable
private fun rememberOsmConfiguration(context: Context) {
    remember(context) {
        Configuration.getInstance().apply {
            @Suppress("DEPRECATION")
            load(context, PreferenceManager.getDefaultSharedPreferences(context))
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir.resolve("osmdroid").apply { mkdirs() }
            osmdroidTileCache = osmdroidBasePath.resolve("tiles").apply { mkdirs() }
        }
        true
    }
}
