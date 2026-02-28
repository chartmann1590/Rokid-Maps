package com.rokid.hud.phone

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lng: Double
)

object NominatimClient {

    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "RokidHudMaps/1.0"

    fun search(query: String, limit: Int = 6): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$BASE_URL?q=$encoded&format=json&limit=$limit&addressdetails=0")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lng = obj.getString("lon").toDouble()
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
