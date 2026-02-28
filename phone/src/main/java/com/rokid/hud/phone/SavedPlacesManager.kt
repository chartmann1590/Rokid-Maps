package com.rokid.hud.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val savedAt: Long = System.currentTimeMillis()
)

class SavedPlacesManager(context: Context) {

    private val prefs = context.getSharedPreferences("rokid_saved_places", Context.MODE_PRIVATE)

    companion object {
        private const val KEY = "places_json"
    }

    fun save(place: SavedPlace) {
        val list = getAll().toMutableList()
        list.removeAll { it.name == place.name && it.lat == place.lat && it.lng == place.lng }
        list.add(0, place)
        persist(list)
    }

    fun getAll(): List<SavedPlace> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SavedPlace(
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    savedAt = obj.optLong("savedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun delete(place: SavedPlace) {
        val list = getAll().toMutableList()
        list.removeAll { it.name == place.name && it.lat == place.lat && it.lng == place.lng }
        persist(list)
    }

    private fun persist(list: List<SavedPlace>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("lat", p.lat)
                put("lng", p.lng)
                put("savedAt", p.savedAt)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
