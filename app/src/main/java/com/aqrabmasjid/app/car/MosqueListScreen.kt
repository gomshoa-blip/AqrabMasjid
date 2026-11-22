package com.aqrabmasjid.app.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.aqrabmasjid.app.LocationHelper
import com.aqrabmasjid.app.Mosque
import com.aqrabmasjid.app.MosqueRepo

/** Nearest mosques on a map. Tap one to start navigation. */
class MosqueListScreen(carContext: CarContext) : Screen(carContext) {

    private var mosques: List<Mosque>? = null
    private var error: String? = null
    private var loading = true

    init {
        load()
    }

    private fun load() {
        loading = true
        LocationHelper.get(carContext) { loc ->
            if (loc == null) {
                loading = false
                error = "لا يوجد موقع. افتح التطبيق في الجوال واسمح بالموقع"
                invalidate()
                return@get
            }
            MosqueRepo.nearby(carContext, loc.first, loc.second) { list ->
                loading = false
                mosques = list
                error = if (list == null) "تعذر الاتصال بالإنترنت" else null
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val b = PlaceListMapTemplate.Builder()
            .setTitle("أقرب المساجد")
            .setHeaderAction(Action.BACK)

        if (loading) return b.setLoading(true).build()

        val list = ItemList.Builder()
        val m = mosques
        if (m.isNullOrEmpty()) {
            list.setNoItemsMessage(error ?: "لا توجد مساجد قريبة")
        } else {
            m.take(placeLimit()).forEach { mo ->
                val place = Place.Builder(CarLocation.create(mo.lat, mo.lng))
                    .setMarker(PlaceMarker.Builder().build())
                    .build()
                list.addItem(
                    Row.Builder()
                        .setTitle(mo.name)
                        .addText(MosqueRepo.fmtDistance(mo.distKm))
                        .setMetadata(Metadata.Builder().setPlace(place).build())
                        .setOnClickListener { navigate(mo) }
                        .build()
                )
            }
        }

        return b.setItemList(list.build())
            .setCurrentLocationEnabled(LocationHelper.hasPermission(carContext))
            .build()
    }

    /** Some cars allow fewer places while driving. */
    private fun placeLimit(): Int {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2) return 6
        return carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
            .coerceAtMost(6)
    }

    private fun navigate(m: Mosque) {
        val uri = Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})")
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "لا يوجد تطبيق ملاحة", CarToast.LENGTH_LONG).show()
        }
    }
}
