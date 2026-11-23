package com.aqrabmasjid.app.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.aqrabmasjid.app.L
import com.aqrabmasjid.app.LocationHelper
import com.aqrabmasjid.app.PrayerCalc
import com.aqrabmasjid.app.Prefs

/** Car home screen: next prayer + today's times + button to nearest mosques. */
class PrayerScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 60_000)
        }
    }

    init {
        lifecycle.addObserver(this)
        LocationHelper.get(carContext) { invalidate() }
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.postDelayed(tick, 60_000)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    private fun openMosques() {
        screenManager.push(MosqueListScreen(carContext))
    }

    override fun onGetTemplate(): Template {
        var headerTitle = L.t("أقرب مسجد", "Nearest Mosque")
        val list = ItemList.Builder()
        val loc = Prefs.location(carContext)

        if (loc == null) {
            list.setNoItemsMessage(L.t("افتح التطبيق في الجوال واسمح بالموقع", "Open the app on your phone and allow location"))
        } else {
            val (lat, lng) = loc
            val now = System.currentTimeMillis()
            val st = PrayerCalc.status(carContext, lat, lng, now)

            val head = if (st.isCurrent) {
                val left = st.time.iqama - now
                if (left > 0) L.t("${st.time.prayer.ar}: الإقامة بعد ${PrayerCalc.fmtLeft(left)}", "${st.time.prayer.ar}: iqama in ${PrayerCalc.fmtLeft(left)}")
                else L.t("الصلاة الحالية: ${st.time.prayer.ar}", "Current prayer: ${st.time.prayer.ar}")
            } else {
                L.t("${st.time.prayer.ar} بعد ${PrayerCalc.fmtLeft(st.time.adhan - now)}", "${st.time.prayer.ar} in ${PrayerCalc.fmtLeft(st.time.adhan - now)}")
            }

            // Driver-first: the coming prayer is the first, highlighted row
            headerTitle = head
            list.addItem(
                Row.Builder()
                    .setTitle(head)
                    .addText(L.t("الأذان ${PrayerCalc.fmtTime(st.time.adhan)} · الإقامة ${PrayerCalc.fmtTime(st.time.iqama)}", "Adhan ${PrayerCalc.fmtTime(st.time.adhan)} · Iqama ${PrayerCalc.fmtTime(st.time.iqama)}"))
                    .build()
            )
            list.addItem(
                Row.Builder()
                    .setTitle(L.t("🧭 خذني لأقرب مسجد", "🧭 Take me to nearest mosque"))
                    .setBrowsable(true)
                    .setOnClickListener { openMosques() }
                    .build()
            )

            // Only the prayers still coming today, so the list stays short
            for (p in PrayerCalc.day(carContext, lat, lng, now).list) {
                if (p.adhan <= now || p.prayer == st.time.prayer) continue
                list.addItem(
                    Row.Builder()
                        .setTitle(p.prayer.ar)
                        .addText(L.t("الأذان ${PrayerCalc.fmtTime(p.adhan)} · الإقامة ${PrayerCalc.fmtTime(p.iqama)}", "Adhan ${PrayerCalc.fmtTime(p.adhan)} · Iqama ${PrayerCalc.fmtTime(p.iqama)}"))
                        .build()
                )
            }
        }

        val builtList = trimToLimit(list.build())

        return ListTemplate.Builder()
            // The coming prayer is also the screen title, so the driver sees it at a glance
            .setTitle(headerTitle)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(builtList)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(L.t("المساجد", "Mosques"))
                            .setOnClickListener { openMosques() }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /** Some cars show fewer rows while driving. */
    private fun trimToLimit(items: ItemList): ItemList {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2) return items
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        if (items.items.size <= limit) return items
        val b = ItemList.Builder()
        items.items.take(limit).forEach { b.addItem(it) }
        return b.build()
    }
}
