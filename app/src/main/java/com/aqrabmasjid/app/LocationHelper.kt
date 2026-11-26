package com.aqrabmasjid.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors

object LocationHelper {
    private val exec = Executors.newSingleThreadExecutor()

    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Gets a fresh location, saves it, and falls back to the last saved one. Runs cb on main thread. */
    @SuppressLint("MissingPermission")
    fun get(c: Context, cb: (Pair<Double, Double>?) -> Unit) {
        val app = c.applicationContext
        val saved = Prefs.location(app)
        if (!hasPermission(app)) {
            cb(saved); return
        }
        val client = LocationServices.getFusedLocationProviderClient(app)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    Prefs.saveLocation(app, loc.latitude, loc.longitude)
                    updateCountry(app, loc.latitude, loc.longitude)
                    cb(loc.latitude to loc.longitude)
                } else {
                    client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            Prefs.saveLocation(app, last.latitude, last.longitude)
                            updateCountry(app, last.latitude, last.longitude)
                            cb(last.latitude to last.longitude)
                        } else cb(saved)
                    }.addOnFailureListener { cb(saved) }
                }
            }
            .addOnFailureListener { cb(saved) }
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
