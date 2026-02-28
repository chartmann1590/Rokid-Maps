package com.rokid.hud.phone.routing

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rokid.hud.shared.protocol.RouteMessage
import com.rokid.hud.shared.protocol.RouteStep
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OSRM public demo API – no key required.
 * https://router.project-osrm.org/route/v1/{profile}/{coordinates}?overview=full&steps=true
 */
class OsrmRouting(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    companion object {
        private const val BASE = "https://router.project-osrm.org/route/v1"
        const val PROFILE_DRIVING = "driving"
        const val PROFILE_WALKING = "walking"
    }

    /**
     * Geocode using Nominatim (OSM). No key.
     */
    suspend fun geocode(query: String): GeocodeResult? {
        val url = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=1"
        val request = Request.Builder().url(url)
            .addHeader("User-Agent", "RokidHUDMaps/1.0")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val list = gson.fromJson(body, Array<NominatimItem>::class.java)
                list.firstOrNull()?.let { GeocodeResult(it.lat.toDouble(), it.lon.toDouble(), it.display_name) }
            }
        }.getOrNull()
    }

    /**
     * Get route from origin to destination. Returns polyline (list of [lat, lon]) and steps.
     * Coordinates: "lon,lat;lon,lat" for OSRM.
     */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String = PROFILE_DRIVING
    ): RouteMessage? {
        val coords = "$fromLon,$fromLat;$toLon,$toLat"
        val url = "$BASE/$profile/$coords?overview=full&steps=true&geometries=geojson"
        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val doc = gson.fromJson(body, OsrmResponse::class.java)
                val route = doc.routes?.firstOrNull() ?: return@runCatching null
                val geometry = route.geometry
                val coordsList = geometry?.coordinates ?: return@runCatching null
                // GeoJSON is [lon, lat]; we want [[lat, lon], ...]
                val polyline = coordsList.map { listOf(it[1], it[0]) }
                val simplified = simplifyPolyline(polyline, 80)
                val steps = route.legs?.flatMap { it.steps ?: emptyList() }?.map { step ->
                    val dist = step.distance ?: 0.0
                    val bearing = step.maneuver?.bearingAfter?.toFloat()
                    RouteStep(
                        instruction = step.maneuver?.modifier?.let { "$it ${step.name?.take(40) ?: ""}" }?.trim()
                            ?: step.name?.take(50) ?: "Continue",
                        distance = dist,
                        bearing = bearing
                    )
                } ?: emptyList()
                RouteMessage(polyline = simplified, steps = steps)
            }
        }.getOrNull()
    }

    /** Douglas-Peucker–style simplification to target point count. */
    private fun simplifyPolyline(points: List<List<Double>>, targetPoints: Int): List<List<Double>> {
        if (points.size <= targetPoints) return points
        val step = (points.size - 1).toDouble() / (targetPoints - 1)
        return (0 until targetPoints).map { i ->
            val idx = (i * step).toInt().coerceAtMost(points.size - 1)
            points[idx]
        }
    }

    data class GeocodeResult(val lat: Double, val lon: Double, val displayName: String)

    private data class NominatimItem(
        val lat: String,
        val lon: String,
        val display_name: String
    )

    private data class OsrmResponse(val routes: List<OsrmRoute>?)
    private data class OsrmRoute(
        val geometry: GeoJsonGeometry?,
        val legs: List<OsrmLeg>?
    )
    private data class GeoJsonGeometry(val coordinates: List<List<Double>>?)
    private data class OsrmLeg(val steps: List<OsrmStep>?)
    private data class OsrmStep(
        val name: String?,
        val distance: Double?,
        val maneuver: OsrmManeuver?
    )
    private data class OsrmManeuver(
        val modifier: String?,
        @SerializedName("bearing_after") val bearingAfter: Double?
    )
}
