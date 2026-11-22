package com.aqrabmasjid.app

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)

    fun location(c: Context): Pair<Double, Double>? {
        val sp = p(c)
        val lat = sp.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lng = sp.getString("lng", null)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    fun saveLocation(c: Context, lat: Double, lng: Double) {
        p(c).edit().putString("lat", lat.toString()).putString("lng", lng.toString()).apply()
    }

    /** ISO country code like "SA", "EG". Null if not known yet. */
    fun country(c: Context): String? = p(c).getString("country", null)
    fun saveCountry(c: Context, code: String) = p(c).edit().putString("country", code).apply()

    /** User iqama minutes for a prayer, or -1 if not set. */
    fun iqama(c: Context, prayer: Prayer): Int = p(c).getInt("iq_${prayer.name}", -1)
    fun setIqama(c: Context, prayer: Prayer, min: Int) =
        p(c).edit().putInt("iq_${prayer.name}", min).apply()

    fun alertsOn(c: Context): Boolean = p(c).getBoolean("alerts", true)
    fun setAlerts(c: Context, on: Boolean) = p(c).edit().putBoolean("alerts", on).apply()

    fun saveNearest(c: Context, m: Mosque) {
        p(c).edit()
            .putString("n_name", m.name)
            .putString("n_lat", m.lat.toString())
            .putString("n_lng", m.lng.toString())
            .apply()
    }

    fun nearest(c: Context): Mosque? {
        val sp = p(c)
        val name = sp.getString("n_name", null) ?: return null
        val lat = sp.getString("n_lat", null)?.toDoubleOrNull() ?: return null
        val lng = sp.getString("n_lng", null)?.toDoubleOrNull() ?: return null
        val here = location(c)
        val d = if (here != null) MosqueRepo.distanceKm(here.first, here.second, lat, lng) else 0.0
        return Mosque(name, lat, lng, d)
    }
}
