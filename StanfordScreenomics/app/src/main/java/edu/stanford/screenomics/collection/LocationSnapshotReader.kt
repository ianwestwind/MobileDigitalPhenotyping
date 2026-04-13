package edu.stanford.screenomics.collection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.app.modules.gps.GpsDataNode

/**
 * Feeds [GpsDataNode] with a coarse stream of fixes while the foreground collection service runs.
 */
class LocationSnapshotReader(
    appContext: Context,
) {
    private val app = appContext.applicationContext
    private val locationManager: LocationManager =
        app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var latest: GpsDataNode.LocationSnapshot? = null
        private set

    private val listener = LocationListener { location: Location ->
        latest = location.toSnapshot()
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).forEach { provider ->
            runCatching {
                locationManager.getLastKnownLocation(provider)?.let { loc ->
                    if (latest == null) {
                        latest = loc.toSnapshot()
                    }
                }
            }
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    25_000L,
                    5f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    30_000L,
                    10f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun Location.toSnapshot(): GpsDataNode.LocationSnapshot =
        GpsDataNode.LocationSnapshot(
            latitudeDegrees = latitude,
            longitudeDegrees = longitude,
            horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
            providerName = provider ?: "unknown",
        )
}
