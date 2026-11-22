package com.aqrabmasjid.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.aqrabmasjid.app.Alerts

/** Entry point for Android Auto. */
class MasjidCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debug) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = MasjidSession()
}

class MasjidSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        if (intent.action == Alerts.ACTION_SHOW_MOSQUES) {
            // Opened from the prayer alert: show mosques, back goes to prayer times
            screenManager.push(PrayerScreen(carContext))
            return MosqueListScreen(carContext)
        }
        return PrayerScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == Alerts.ACTION_SHOW_MOSQUES) {
            if (screenManager.top !is MosqueListScreen) {
                screenManager.push(MosqueListScreen(carContext))
            }
        }
    }
}
