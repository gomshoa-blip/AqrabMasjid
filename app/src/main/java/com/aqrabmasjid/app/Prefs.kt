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
        p(c).edit().putString("lat", lat.toString()).putString("lng", lng.toString())
            .putLong("loc_t", System.currentTimeMillis()).apply()
    }

    /** Ramadan: alert before Fajr for suhoor (on by default). */
    fun suhoorOn(c: Context): Boolean = p(c).getBoolean("suhoor_on", true)
    fun setSuhoor(c: Context, on: Boolean) = p(c).edit().putBoolean("suhoor_on", on).apply()

    /** When the location was last updated (0 = not known). */
    fun locationTime(c: Context): Long = p(c).getLong("loc_t", 0L)

    /** ISO country code like "SA", "EG". Null if not known yet. */
    fun country(c: Context): String? = p(c).getString("country", null)

    /** City of the last location (Arabic and English names), for the prayer table title. */
    fun saveCity(c: Context, ar: String, en: String, lat: Double, lng: Double) {
        p(c).edit().putString("city_ar", ar).putString("city_en", en)
            .putString("city_lat", lat.toString()).putString("city_lng", lng.toString()).apply()
    }

    /** Place name (district, city) in the app language, only if it was found near the place used now (about 3 km). */
    fun city(c: Context, lat: Double, lng: Double): String? {
        val sp = p(c)
        val la = sp.getString("city_lat", null)?.toDoubleOrNull() ?: return null
        val lo = sp.getString("city_lng", null)?.toDoubleOrNull() ?: return null
        if (MosqueRepo.distanceKm(lat, lng, la, lo) > 3) return null
        val ar = sp.getString("city_ar", null); val en = sp.getString("city_en", null)
        return L.t(ar ?: en ?: return null, en ?: ar ?: return null).takeIf { it.isNotBlank() }
    }
    fun saveCountry(c: Context, code: String) = p(c).edit().putString("country", code).apply()

    /** User iqama minutes for a prayer, or -1 if not set. */
    fun iqama(c: Context, prayer: Prayer): Int = p(c).getInt("iq_${prayer.name}", -1)
    fun setIqama(c: Context, prayer: Prayer, min: Int) =
        p(c).edit().putInt("iq_${prayer.name}", min).apply()

    fun alertsOn(c: Context): Boolean = p(c).getBoolean("alerts", true)
    fun setAlerts(c: Context, on: Boolean) = p(c).edit().putBoolean("alerts", on).apply()

    /** Minutes before adhan for the "adhan soon" alert: 5, 10, 15 or 20. */
    fun preMin(c: Context): Int = p(c).getInt("pre_min", 15)
    fun setPreMin(c: Context, m: Int) = p(c).edit().putInt("pre_min", m).apply()

    /** Look: "auto" (follow the phone), "day" or "night". */
    fun theme(c: Context): String = p(c).getString("theme", "auto") ?: "auto"
    fun setTheme(c: Context, t: String) = p(c).edit().putString("theme", t).apply()

    /** The two favorite radio stations on the radio card. */
    fun favRadio(c: Context, slot: Int): String =
        p(c).getString("fav_radio_$slot", null) ?: if (slot == 0) "quran" else "sunnah"
    fun setFavRadio(c: Context, slot: Int, id: String) = p(c).edit().putString("fav_radio_$slot", id).apply()

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
