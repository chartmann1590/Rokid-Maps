package com.rokid.hud.shared.protocol

object ProtocolConstants {
    const val FIELD_TYPE = "t"
    const val FIELD_LATITUDE = "lat"
    const val FIELD_LONGITUDE = "lng"
    const val FIELD_BEARING = "bearing"
    const val FIELD_SPEED = "speed"
    const val FIELD_ACCURACY = "accuracy"
    const val FIELD_WAYPOINTS = "waypoints"
    const val FIELD_DISTANCE = "distance"
    const val FIELD_DURATION = "duration"
    const val FIELD_INSTRUCTION = "instruction"
    const val FIELD_MANEUVER = "maneuver"
    const val FIELD_STEP_DISTANCE = "stepDistance"
    const val FIELD_TITLE = "title"
    const val FIELD_TEXT = "text"
    const val FIELD_PACKAGE_NAME = "packageName"
    const val FIELD_TIME_MS = "timeMs"
    const val FIELD_TTS_ENABLED = "ttsEnabled"
    const val FIELD_USE_IMPERIAL = "useImperial"
    const val FIELD_WIFI_SSID = "wifiSsid"
    const val FIELD_WIFI_PASS = "wifiPass"
    const val FIELD_WIFI_ENABLED = "wifiEnabled"

    object MessageType {
        const val STATE = "state"
        const val ROUTE = "route"
        const val STEP = "step"
        const val NOTIFICATION = "notification"
        const val SETTINGS = "settings"
        const val WIFI_CREDS = "wifi_creds"
    }
}
