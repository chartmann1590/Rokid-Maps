package com.rokid.hud.phone

import com.rokid.hud.shared.protocol.Waypoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class NavigationStep(
    val instruction: String,
    val maneuver: String,
    val distance: Double,
    val duration: Double,
    val locationLat: Double,
    val locationLng: Double
)

data class RouteResult(
    val waypoints: List<Waypoint>,
    val steps: List<NavigationStep>,
    val totalDistance: Double,
    val totalDuration: Double
)

object OsrmClient {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    private const val USER_AGENT = "RokidHudMaps/1.0"

    fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RouteResult {
        val url = URL("$BASE_URL/$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=geojson&steps=true")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            parseRouteResponse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRouteResponse(body: String): RouteResult {
        val json = JSONObject(body)
        if (json.getString("code") != "Ok") {
            throw RuntimeException("OSRM error: ${json.optString("message", json.getString("code"))}")
        }

        val route = json.getJSONArray("routes").getJSONObject(0)
        val totalDistance = route.getDouble("distance")
        val totalDuration = route.getDouble("duration")

        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val waypoints = mutableListOf<Waypoint>()
        val stride = maxOf(1, coords.length() / 500)
        for (i in 0 until coords.length() step stride) {
            val c = coords.getJSONArray(i)
            waypoints.add(Waypoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
        }
        if (waypoints.size > 1) {
            val last = coords.getJSONArray(coords.length() - 1)
            val lastWp = Waypoint(latitude = last.getDouble(1), longitude = last.getDouble(0))
            if (waypoints.last() != lastWp) waypoints.add(lastWp)
        }

        val steps = mutableListOf<NavigationStep>()
        val legs = route.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until legSteps.length()) {
                val s = legSteps.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.getString("type")
                val modifier = m.optString("modifier", "")
                val name = s.optString("name", "")

                steps.add(NavigationStep(
                    instruction = buildInstruction(type, modifier, name),
                    maneuver = toManeuverKey(type, modifier),
                    distance = s.getDouble("distance"),
                    duration = s.getDouble("duration"),
                    locationLat = loc.getDouble(1),
                    locationLng = loc.getDouble(0)
                ))
            }
        }

        return RouteResult(waypoints, steps, totalDistance, totalDuration)
    }

    private fun buildInstruction(type: String, modifier: String, name: String): String {
        val street = if (name.isBlank()) "" else " onto $name"
        return when (type) {
            "depart" -> "Head${street.ifEmpty { " out" }}"
            "arrive" -> "Arrive at destination"
            "turn" -> "${modifierLabel(modifier)}$street"
            "new name" -> "Continue$street"
            "merge" -> "Merge$street"
            "on ramp" -> "Take ramp$street"
            "off ramp" -> "Exit$street"
            "fork" -> "${modifierLabel(modifier)} at fork$street"
            "end of road" -> "${modifierLabel(modifier)}$street"
            "continue" -> "Continue$street"
            "roundabout", "rotary" -> "Enter roundabout, exit$street"
            "roundabout turn" -> "${modifierLabel(modifier)} at roundabout$street"
            "notification" -> name.ifBlank { "Continue" }
            else -> "Continue$street"
        }
    }

    private fun modifierLabel(modifier: String): String = when (modifier) {
        "left" -> "Turn left"
        "right" -> "Turn right"
        "straight" -> "Continue straight"
        "slight left" -> "Slight left"
        "slight right" -> "Slight right"
        "sharp left" -> "Sharp left"
        "sharp right" -> "Sharp right"
        "uturn" -> "Make a U-turn"
        else -> "Continue"
    }

    private fun toManeuverKey(type: String, modifier: String): String = when {
        type == "arrive" -> "arrive"
        type == "depart" -> "depart"
        type == "roundabout" || type == "rotary" -> modifier.ifBlank { "straight" }
        modifier.isNotBlank() -> modifier
        else -> "straight"
    }
}
