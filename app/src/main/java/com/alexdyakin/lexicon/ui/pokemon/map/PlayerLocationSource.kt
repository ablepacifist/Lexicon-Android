package com.alexdyakin.lexicon.ui.pokemon.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A GPS fix. Separate from the API models so the map can hold a position before any call. */
data class PlayerFix(val lat: Double, val lng: Double, val accuracyM: Float)

/**
 * The player's position, from the platform's own [LocationManager].
 *
 * Deliberately **not** Play Services' fused provider: the app has no Play Services
 * dependency and the map is built on OpenStreetMap precisely so that no Google
 * services or API keys are needed. The fused provider is smoother, but adding it
 * for this one screen would pull that whole stack back in.
 *
 * Both GPS and network providers are registered and whichever speaks first wins,
 * because indoors GPS can take a minute while the network provider answers at once.
 * Accuracy is passed through so the map can draw an honest uncertainty circle
 * instead of implying a metre-perfect fix.
 */
@Singleton
class PlayerLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True when either location permission is granted. Coarse is enough to start. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the user has location switched off system-wide; no provider will ever fire. */
    fun locationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    /**
     * A stream of fixes, starting with the last known position when there is one so
     * the map can centre immediately rather than opening on a blank ocean.
     *
     * The flow closes itself if permission is missing, so collectors see completion
     * rather than hanging forever waiting for a fix that cannot come.
     */
    fun updates(minIntervalMs: Long = 5_000L, minDistanceM: Float = 5f): Flow<PlayerFix> =
        callbackFlow {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (manager == null || !hasPermission()) {
                close()
                return@callbackFlow
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(PlayerFix(location.latitude, location.longitude, location.accuracy))
                }

                // Required on API < 30; without them some OEM builds throw AbstractMethodError.
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Removed in API 29 but still called on older devices")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

            // A cached fix first: `requestLocationUpdates` can take a long time to produce
            // the first one, and an immediately-centred map that then refines itself reads
            // far better than a blank one.
            providers
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
                ?.let { trySend(PlayerFix(it.latitude, it.longitude, it.accuracy)) }

            var registered = 0
            providers.forEach { provider ->
                // Absent providers throw rather than returning false on some devices, and a
                // missing network provider must not stop GPS from being registered.
                runCatching {
                    if (manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(
                            provider, minIntervalMs, minDistanceM, listener,
                        )
                        registered++
                    }
                }
            }
            if (registered == 0) close()

            awaitClose { runCatching { manager.removeUpdates(listener) } }
        }
}
