package com.freelocation.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 收藏的位置，保存在本地 SharedPreferences。坐标为 GCJ-02（与地图一致）。 */
class Favorites(context: Context) {
    data class Place(val name: String, val lat: Double, val lng: Double)

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun list(): List<Place> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(place: Place) = save(list().filterNot { it.name == place.name } + place)

    fun remove(name: String) = save(list().filterNot { it.name == name })

    private fun save(items: List<Place>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 记住上次选中的点，下次打开时地图定位到这里。 */
    var lastPoint: Pair<Double, Double>?
        get() {
            if (!prefs.contains("last_lat_bits")) return null
            val lat = java.lang.Double.longBitsToDouble(prefs.getLong("last_lat_bits", 0))
            val lng = java.lang.Double.longBitsToDouble(prefs.getLong("last_lng_bits", 0))
            return lat to lng
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("last_lat_bits").remove("last_lng_bits")
            else e.putLong("last_lat_bits", java.lang.Double.doubleToRawLongBits(v.first))
                .putLong("last_lng_bits", java.lang.Double.doubleToRawLongBits(v.second))
            e.apply()
        }

    private companion object {
        const val KEY = "items"
    }
}
