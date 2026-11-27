package com.aqrabmasjid.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors

object LocationHelper {
    private val exec = Executors.newSingleThreadExecutor()

    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Gets a fresh location, saves it, and falls back to the last saved one. Runs cb on main thread.
     * Uses Google location first. Phones without Google services (some Honor, all new Huawei)
     * use the phone's own GPS / network location instead.
     */
    @SuppressLint("MissingPermission")
    fun get(c: Context, cb: (Pair<Double, Double>?) -> Unit) {
        val app = c.applicationContext
        val saved = Prefs.location(app)
        if (!hasPermission(app)) {
            cb(saved); return
        }
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(loc: Location?) {
            if (done) return
            done = true
            if (loc != null) {
                Prefs.saveLocation(app, loc.latitude, loc.longitude)
                updateCountry(app, loc.latitude, loc.longitude)
                cb(loc.latitude to loc.longitude)
            } else cb(Prefs.location(app) ?: saved)
        }
        // Google did not answer: use the phone's own location
        fun fallback() { if (!done) systemLocation(app) { finish(it) } }

        if (!hasGoogle(app)) { fallback(); return }
        // Safety: if Google location hangs, switch after 8 seconds
        main.postDelayed({ fallback() }, 8000)
        try {
            val client = LocationServices.getFusedLocationProviderClient(app)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) finish(loc)
                    else client.lastLocation
                        .addOnSuccessListener { last -> if (last != null) finish(last) else fallback() }
                        .addOnFailureListener { fallback() }
                }
                .addOnFailureListener { fallback() }
        } catch (_: Exception) {
            fallback()
        }
    }

    /** True when Google Play services work on this phone. */
    private fun hasGoogle(c: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS
    } catch (_: Exception) { false }

    /** The phone's own location (GPS / network), without Google. Waits up to 12 seconds. */
    @SuppressLint("MissingPermission")
    private fun systemLocation(c: Context, cb: (Location?) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return cb(null)
        val main = Handler(Looper.getMainLooper())
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { p -> try { lm.isProviderEnabled(p) } catch (_: Exception) { false } }
        // Newest last known location
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60 * 1000) { cb(last); return }
        val live = providers.filter { it != LocationManager.PASSIVE_PROVIDER }
        if (live.isEmpty()) { cb(last); return }

        var done = false
        val listeners = mutableListOf<LocationListener>()
        fun finish(loc: Location?) {
            if (done) return
            done = true
            listeners.forEach { l -> try { lm.removeUpdates(l) } catch (_: Exception) {} }
            cb(loc ?: last)
        }
        for (p in live) {
            val l = object : LocationListener {
                override fun onLocationChanged(loc: Location) = finish(loc)
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listeners.add(l)
            try { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        main.postDelayed({ finish(null) }, 12000)
    }

    /** Location picked by the user on the map (when the phone cannot find it). */
    fun saveManual(c: Context, lat: Double, lng: Double) {
        val app = c.applicationContext
        Prefs.saveLocation(app, lat, lng)
        updateCountry(app, lat, lng)
    }

    /** Finds the country code in the background (used for prayer method and iqama). */
    private fun updateCountry(c: Context, lat: Double, lng: Double) {
        exec.execute {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(c, Locale.ENGLISH).getFromLocation(lat, lng, 1)
                val code = list?.firstOrNull()?.countryCode
                if (!code.isNullOrBlank()) Prefs.saveCountry(c, code.uppercase(Locale.US))
                // City name for the prayer table, in Arabic and English
                val en = list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                @Suppress("DEPRECATION")
                val ar = Geocoder(c, Locale("ar")).getFromLocation(lat, lng, 1)?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                if (!en.isNullOrBlank() || !ar.isNullOrBlank()) Prefs.saveCity(c, ar ?: en ?: "", en ?: ar ?: "", lat, lng)
            } catch (_: Exception) {
            }
        }
    }
}
