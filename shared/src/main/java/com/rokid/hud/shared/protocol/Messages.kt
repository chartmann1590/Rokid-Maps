package com.rokid.hud.shared.protocol

data class StateMessage(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speed: Float,
    val accuracy: Float
)

data class Waypoint(
    val latitude: Double,
    val longitude: Double
)

data class RouteMessage(
    val waypoints: List<Waypoint>,
    val totalDistance: Double,
    val totalDuration: Double
)

data class StepMessage(
    val instruction: String,
    val maneuver: String,
    val distance: Double
)

data class NotificationMessage(
    val title: String?,
    val text: String?,
    val packageName: String?,
    val timeMs: Long
)

data class SettingsMessage(
    val ttsEnabled: Boolean,
    val useImperial: Boolean = false
)

data class WifiCredsMessage(
    val ssid: String,
    val passphrase: String,
    val enabled: Boolean
)
