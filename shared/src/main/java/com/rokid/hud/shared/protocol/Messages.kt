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
    val useImperial: Boolean = false,
    val useMiniMap: Boolean = false
)

data class WifiCredsMessage(
    val ssid: String,
    val passphrase: String,
    val enabled: Boolean
)

data class TileRequestMessage(val id: String, val z: Int, val x: Int, val y: Int)

data class TileResponseMessage(val id: String, val data: String?)

data class ApkStartMessage(val totalSize: Long, val totalChunks: Int)
data class ApkChunkMessage(val index: Int, val data: String)
data class ApkEndMessage(val placeholder: Boolean = true)
