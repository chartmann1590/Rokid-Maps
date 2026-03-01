package com.rokid.hud.shared.protocol

import org.json.JSONArray
import org.json.JSONObject

sealed class ParsedMessage {
    data class State(val msg: StateMessage) : ParsedMessage()
    data class Route(val msg: RouteMessage) : ParsedMessage()
    data class Step(val msg: StepMessage) : ParsedMessage()
    data class Notification(val msg: NotificationMessage) : ParsedMessage()
    data class Settings(val msg: SettingsMessage) : ParsedMessage()
    data class WifiCreds(val msg: WifiCredsMessage) : ParsedMessage()
    data class TileReq(val msg: TileRequestMessage) : ParsedMessage()
    data class TileResp(val msg: TileResponseMessage) : ParsedMessage()
    data class ApkStart(val msg: ApkStartMessage) : ParsedMessage()
    data class ApkChunk(val msg: ApkChunkMessage) : ParsedMessage()
    data class ApkEnd(val msg: ApkEndMessage) : ParsedMessage()
    data class Unknown(val raw: String) : ParsedMessage()
}

object ProtocolCodec {

    fun encodeState(msg: StateMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.STATE)
        put(ProtocolConstants.FIELD_LATITUDE, msg.latitude)
        put(ProtocolConstants.FIELD_LONGITUDE, msg.longitude)
        put(ProtocolConstants.FIELD_BEARING, msg.bearing.toDouble())
        put(ProtocolConstants.FIELD_SPEED, msg.speed.toDouble())
        put(ProtocolConstants.FIELD_ACCURACY, msg.accuracy.toDouble())
    }.toString()

    fun encodeRoute(msg: RouteMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.ROUTE)
        put(ProtocolConstants.FIELD_WAYPOINTS, JSONArray().apply {
            for (wp in msg.waypoints) {
                put(JSONObject().apply {
                    put(ProtocolConstants.FIELD_LATITUDE, wp.latitude)
                    put(ProtocolConstants.FIELD_LONGITUDE, wp.longitude)
                })
            }
        })
        put(ProtocolConstants.FIELD_DISTANCE, msg.totalDistance)
        put(ProtocolConstants.FIELD_DURATION, msg.totalDuration)
    }.toString()

    fun encodeStep(msg: StepMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.STEP)
        put(ProtocolConstants.FIELD_INSTRUCTION, msg.instruction)
        put(ProtocolConstants.FIELD_MANEUVER, msg.maneuver)
        put(ProtocolConstants.FIELD_STEP_DISTANCE, msg.distance)
    }.toString()

    fun encodeNotification(msg: NotificationMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.NOTIFICATION)
        put(ProtocolConstants.FIELD_TITLE, msg.title ?: "")
        put(ProtocolConstants.FIELD_TEXT, msg.text ?: "")
        put(ProtocolConstants.FIELD_PACKAGE_NAME, msg.packageName ?: "")
        put(ProtocolConstants.FIELD_TIME_MS, msg.timeMs)
    }.toString()

    fun encodeSettings(msg: SettingsMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.SETTINGS)
        put(ProtocolConstants.FIELD_TTS_ENABLED, msg.ttsEnabled)
        put(ProtocolConstants.FIELD_USE_IMPERIAL, msg.useImperial)
        put(ProtocolConstants.FIELD_USE_MINI_MAP, msg.useMiniMap)
        put(ProtocolConstants.FIELD_MINI_MAP_STYLE, msg.miniMapStyle)
    }.toString()

    fun encodeWifiCreds(msg: WifiCredsMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.WIFI_CREDS)
        put(ProtocolConstants.FIELD_WIFI_SSID, msg.ssid)
        put(ProtocolConstants.FIELD_WIFI_PASS, msg.passphrase)
        put(ProtocolConstants.FIELD_WIFI_ENABLED, msg.enabled)
    }.toString()

    fun encodeTileReq(msg: TileRequestMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.TILE_REQ)
        put(ProtocolConstants.FIELD_TILE_ID, msg.id)
        put(ProtocolConstants.FIELD_TILE_Z, msg.z)
        put(ProtocolConstants.FIELD_TILE_X, msg.x)
        put(ProtocolConstants.FIELD_TILE_Y, msg.y)
    }.toString()

    fun encodeTileResp(msg: TileResponseMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.TILE_RESP)
        put(ProtocolConstants.FIELD_TILE_ID, msg.id)
        put(ProtocolConstants.FIELD_TILE_DATA, msg.data ?: "")
    }.toString()

    fun encodeApkStart(msg: ApkStartMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.APK_START)
        put(ProtocolConstants.FIELD_APK_SIZE, msg.totalSize)
        put(ProtocolConstants.FIELD_APK_CHUNKS, msg.totalChunks)
    }.toString()

    fun encodeApkChunk(msg: ApkChunkMessage): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.APK_CHUNK)
        put(ProtocolConstants.FIELD_APK_INDEX, msg.index)
        put(ProtocolConstants.FIELD_TILE_DATA, msg.data)
    }.toString()

    fun encodeApkEnd(): String = JSONObject().apply {
        put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.APK_END)
    }.toString()

    fun decode(line: String): ParsedMessage {
        return try {
            val json = JSONObject(line)
            when (json.optString(ProtocolConstants.FIELD_TYPE)) {
                ProtocolConstants.MessageType.STATE -> ParsedMessage.State(
                    StateMessage(
                        latitude = json.getDouble(ProtocolConstants.FIELD_LATITUDE),
                        longitude = json.getDouble(ProtocolConstants.FIELD_LONGITUDE),
                        bearing = json.getDouble(ProtocolConstants.FIELD_BEARING).toFloat(),
                        speed = json.getDouble(ProtocolConstants.FIELD_SPEED).toFloat(),
                        accuracy = json.getDouble(ProtocolConstants.FIELD_ACCURACY).toFloat()
                    )
                )
                ProtocolConstants.MessageType.ROUTE -> {
                    val wpArray = json.getJSONArray(ProtocolConstants.FIELD_WAYPOINTS)
                    val waypoints = (0 until wpArray.length()).map { i ->
                        val wp = wpArray.getJSONObject(i)
                        Waypoint(
                            wp.getDouble(ProtocolConstants.FIELD_LATITUDE),
                            wp.getDouble(ProtocolConstants.FIELD_LONGITUDE)
                        )
                    }
                    ParsedMessage.Route(
                        RouteMessage(
                            waypoints = waypoints,
                            totalDistance = json.getDouble(ProtocolConstants.FIELD_DISTANCE),
                            totalDuration = json.getDouble(ProtocolConstants.FIELD_DURATION)
                        )
                    )
                }
                ProtocolConstants.MessageType.STEP -> ParsedMessage.Step(
                    StepMessage(
                        instruction = json.getString(ProtocolConstants.FIELD_INSTRUCTION),
                        maneuver = json.getString(ProtocolConstants.FIELD_MANEUVER),
                        distance = json.getDouble(ProtocolConstants.FIELD_STEP_DISTANCE)
                    )
                )
                ProtocolConstants.MessageType.NOTIFICATION -> ParsedMessage.Notification(
                    NotificationMessage(
                        title = json.optString(ProtocolConstants.FIELD_TITLE, null),
                        text = json.optString(ProtocolConstants.FIELD_TEXT, null),
                        packageName = json.optString(ProtocolConstants.FIELD_PACKAGE_NAME, null),
                        timeMs = json.getLong(ProtocolConstants.FIELD_TIME_MS)
                    )
                )
                ProtocolConstants.MessageType.SETTINGS -> ParsedMessage.Settings(
                    SettingsMessage(
                        ttsEnabled = json.optBoolean(ProtocolConstants.FIELD_TTS_ENABLED, false),
                        useImperial = json.optBoolean(ProtocolConstants.FIELD_USE_IMPERIAL, false),
                        useMiniMap = json.optBoolean(ProtocolConstants.FIELD_USE_MINI_MAP, false),
                        miniMapStyle = json.optString(ProtocolConstants.FIELD_MINI_MAP_STYLE, "strip")
                    )
                )
                ProtocolConstants.MessageType.WIFI_CREDS -> ParsedMessage.WifiCreds(
                    WifiCredsMessage(
                        ssid = json.optString(ProtocolConstants.FIELD_WIFI_SSID, ""),
                        passphrase = json.optString(ProtocolConstants.FIELD_WIFI_PASS, ""),
                        enabled = json.optBoolean(ProtocolConstants.FIELD_WIFI_ENABLED, false)
                    )
                )
                ProtocolConstants.MessageType.TILE_REQ -> ParsedMessage.TileReq(
                    TileRequestMessage(
                        id = json.getString(ProtocolConstants.FIELD_TILE_ID),
                        z = json.getInt(ProtocolConstants.FIELD_TILE_Z),
                        x = json.getInt(ProtocolConstants.FIELD_TILE_X),
                        y = json.getInt(ProtocolConstants.FIELD_TILE_Y)
                    )
                )
                ProtocolConstants.MessageType.TILE_RESP -> ParsedMessage.TileResp(
                    TileResponseMessage(
                        id = json.getString(ProtocolConstants.FIELD_TILE_ID),
                        data = json.optString(ProtocolConstants.FIELD_TILE_DATA, null).takeIf { it?.isNotEmpty() == true }
                    )
                )
                ProtocolConstants.MessageType.APK_START -> ParsedMessage.ApkStart(
                    ApkStartMessage(
                        totalSize = json.getLong(ProtocolConstants.FIELD_APK_SIZE),
                        totalChunks = json.getInt(ProtocolConstants.FIELD_APK_CHUNKS)
                    )
                )
                ProtocolConstants.MessageType.APK_CHUNK -> ParsedMessage.ApkChunk(
                    ApkChunkMessage(
                        index = json.getInt(ProtocolConstants.FIELD_APK_INDEX),
                        data = json.getString(ProtocolConstants.FIELD_TILE_DATA)
                    )
                )
                ProtocolConstants.MessageType.APK_END -> ParsedMessage.ApkEnd(ApkEndMessage())
                else -> ParsedMessage.Unknown(line)
            }
        } catch (e: Exception) {
            ParsedMessage.Unknown(line)
        }
    }
}
