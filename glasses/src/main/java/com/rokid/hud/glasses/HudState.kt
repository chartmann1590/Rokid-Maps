package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.StepInfo
import com.rokid.hud.shared.protocol.Waypoint

enum class MapLayoutMode {
    FULL_SCREEN,
    SMALL_CORNER,
    /** Phone-controlled: 25% map strip at bottom, direction+distance text, no notifications */
    MINI_BOTTOM,
    /** Phone-controlled: bottom 25% split — map on left, directions on right, no notifications */
    MINI_SPLIT
}

data class NotificationItem(
    val title: String?,
    val text: String?,
    val packageName: String?,
    val timeMs: Long
)

data class HudState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val accuracy: Float = 0f,
    val waypoints: List<Waypoint> = emptyList(),
    val totalDistance: Double = 0.0,
    val totalDuration: Double = 0.0,
    val instruction: String = "",
    val maneuver: String = "",
    val stepDistance: Double = 0.0,
    val notifications: List<NotificationItem> = emptyList(),
    val layoutMode: MapLayoutMode = MapLayoutMode.FULL_SCREEN,
    val ttsEnabled: Boolean = false,
    val useImperial: Boolean = false,
    val streamNotifications: Boolean = true,
    val showUpcomingSteps: Boolean = false,
    val allSteps: List<StepInfo> = emptyList(),
    val currentStepIndex: Int = 0,
    val batteryLevel: Int = -1,
    val btConnected: Boolean = false,
    val wifiConnected: Boolean = false
) {
    companion object {
        const val MAX_NOTIFICATIONS = 8
    }

    fun withNotification(item: NotificationItem): HudState {
        val updated = (listOf(item) + notifications).take(MAX_NOTIFICATIONS)
        return copy(notifications = updated)
    }

    fun toggleLayout(): HudState = copy(
        layoutMode = when (layoutMode) {
            MapLayoutMode.FULL_SCREEN -> MapLayoutMode.SMALL_CORNER
            MapLayoutMode.SMALL_CORNER -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_BOTTOM -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_SPLIT -> MapLayoutMode.FULL_SCREEN
        }
    )
}
