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
        val list = ItemList.Builder()
        val loc = Prefs.location(carContext)

        if (loc == null) {
            list.setNoItemsMessage("افتح التطبيق في الجوال واسمح بالموقع")
        } else {
            val (lat, lng) = loc
            val now = System.currentTimeMillis()
            val st = PrayerCalc.status(carContext, lat, lng, now)

            val head = if (st.isCurrent) {
                val left = st.time.iqama - now
                if (left > 0) "${st.time.prayer.ar}: الإقامة بعد ${PrayerCalc.fmtLeft(left)}"
                else "الصلاة الحالية: ${st.time.prayer.ar}"
            } else {
                "${st.time.prayer.ar} بعد ${PrayerCalc.fmtLeft(st.time.adhan - now)}"
            }

            list.addItem(
                Row.Builder()
                    .setTitle(head)
                    .addText("اضغط هنا لأقرب مسجد")
                    .setBrowsable(true)
                    .setOnClickListener { openMosques() }
                    .build()
            )

            for (p in PrayerCalc.day(carContext, lat, lng, now).list) {
                list.addItem(
                    Row.Builder()
                        .setTitle(p.prayer.ar)
                        .addText("الأذان ${PrayerCalc.fmtTime(p.adhan)} · الإقامة ${PrayerCalc.fmtTime(p.iqama)}")
                        .build()
                )
            }
        }

        val builtList = trimToLimit(list.build())

        return ListTemplate.Builder()
            .setTitle("أقرب مسجد")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(builtList)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("المساجد")
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
