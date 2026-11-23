package com.aqrabmasjid.app

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens Google Maps with a search for mosques, so the user picks one.
 * - "geo:0,0?q=..." lets Maps use the current location.
 * - If Google Maps is already navigating, Maps shows the results along the route.
 * We do NOT start navigation to a random mosque.
 */
object MapsSearch {
    private const val MAPS = "com.google.android.apps.maps"

    fun mosques(c: Context) {
        val q = Uri.encode(L.t("مسجد", "mosque"))
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 1) Google Maps app
        try {
            c.startActivity(Intent(geo).setPackage(MAPS))
            return
        } catch (_: Exception) {
        }
        // 2) Any other maps app
        try {
            c.startActivity(geo)
            return
        } catch (_: Exception) {
        }
        // 3) Google Maps website
        try {
            c.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
