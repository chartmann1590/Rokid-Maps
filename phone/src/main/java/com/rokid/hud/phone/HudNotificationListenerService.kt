package com.rokid.hud.phone

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Base64

class HudNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "HudNotifListener"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val PREFS_HUD = "rokid_hud_prefs"
        private const val PREF_LAST_GMAPS_DEBUG = "last_gmaps_debug"
        private const val PREF_STREAMING_ACTIVE = "streaming_active"
        const val ACTION_STREAMING_STATE_CHANGED = "com.rokid.hud.phone.ACTION_STREAMING_STATE_CHANGED"
        const val EXTRA_STREAMING_ACTIVE = "streaming_active"
    }

    private var streamingService: HudStreamingService? = null
    private var bound = false
    private var receiverRegistered = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            streamingService = (service as HudStreamingService.LocalBinder).getService()
            bound = true
            syncActiveGoogleMapsNotification()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
        }
    }

    private val streamingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra(EXTRA_STREAMING_ACTIVE, false) == true
            if (active) {
                bindStreamingService()
                syncActiveGoogleMapsNotification()
            } else {
                unbindStreamingService()
            }
        }
    }

    private fun isStreamingActive(): Boolean {
        return getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .getBoolean(PREF_STREAMING_ACTIVE, false)
    }

    private fun bindStreamingService() {
        if (bound) return
        val intent = Intent(this, HudStreamingService::class.java)
        val boundNow = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindStreamingService: bound=$boundNow")
    }

    private fun unbindStreamingService() {
        if (!bound) return
        try {
            unbindService(connection)
        } catch (_: Exception) {
        }
        streamingService = null
        bound = false
        Log.i(TAG, "unbindStreamingService")
    }

    private fun ensureStreamingServiceForActiveSession(): Boolean {
        if (!isStreamingActive()) {
            unbindStreamingService()
            return false
        }
        if (!bound || streamingService == null) {
            bindStreamingService()
            return streamingService != null
        }
        return true
    }

    private fun syncActiveGoogleMapsNotification() {
        if (!isStreamingActive() || !isGoogleMapsModeEnabled()) return
        if (!bound || streamingService == null) return

        val activeGoogleMapsNotification = try {
            activeNotifications?.firstOrNull { it.packageName == GOOGLE_MAPS_PACKAGE }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect active notifications: ${e.message}")
            null
        }

        if (activeGoogleMapsNotification != null) {
            Log.i(TAG, "Resyncing active Google Maps notification")
            onNotificationPosted(activeGoogleMapsNotification)
        } else {
            streamingService?.clearExternalNavigationStep()
            saveGoogleMapsDebug("noActiveGoogleMapsNotification")
        }
    }

    private fun isGoogleMapsModeEnabled(): Boolean {
        return getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .getBoolean("google_maps_mode", false)
    }

    private fun saveGoogleMapsDebug(debugText: String) {
        getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_GMAPS_DEBUG, debugText)
            .apply()
    }

    private fun describeIcon(icon: Icon?): String {
        if (icon == null) return ""
        return try {
            when (icon.type) {
                Icon.TYPE_RESOURCE -> {
                    val pkg = icon.resPackage.takeIf { it.isNotBlank() } ?: GOOGLE_MAPS_PACKAGE
                    val res = packageManager.getResourcesForApplication(pkg)
                    val entry = res.getResourceEntryName(icon.resId)
                    val type = res.getResourceTypeName(icon.resId)
                    "$pkg:$type/$entry"
                }
                else -> "type=${icon.type}"
            }
        } catch (e: Exception) {
            "type=${icon.type} err=${e.message}"
        }
    }

    private fun maneuverFromIcon(icon: Icon?): String? {
        val desc = describeIcon(icon).lowercase()
        if (desc.isBlank()) return null
        return when {
            desc.contains("uturn") || desc.contains("u_turn") || desc.contains("u-turn") -> "uturn"
            desc.contains("sharp_left") || desc.contains("turn_left") || desc.contains("keep_left") || desc.contains("slight_left") || desc.contains("left") -> "left"
            desc.contains("sharp_right") || desc.contains("turn_right") || desc.contains("keep_right") || desc.contains("slight_right") || desc.contains("right") -> "right"
            desc.contains("exit") || desc.contains("ramp") -> "ramp"
            desc.contains("merge") -> "merge"
            desc.contains("arrive") || desc.contains("destination") -> "arrive"
            desc.contains("fork") || desc.contains("roundabout") -> "fork"
            desc.contains("straight") || desc.contains("continue") || desc.contains("head") -> "straight"
            else -> null
        }
    }

    private fun chooseManeuver(parsedManeuver: String, iconManeuver: String?): String {
        if (iconManeuver.isNullOrBlank()) return parsedManeuver
        return if (parsedManeuver == "straight" && iconManeuver != "straight") {
            iconManeuver
        } else {
            parsedManeuver
        }
    }

    private fun bitmapFromDrawable(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun encodeIcon(icon: Icon?): String? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(this) ?: return null
            val bitmap = bitmapFromDrawable(drawable)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    private fun extractArrowIconData(notification: Notification): String? {
        return encodeIcon(notification.getLargeIcon()) ?: encodeIcon(notification.smallIcon)
    }

    private fun describeGoogleMapsNotification(notification: Notification): String {
        val extras = notification.extras
        val parts = mutableListOf<String>()
        fun add(label: String, value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) parts.add("$label=$text")
        }
        add("title", extras?.getCharSequence(Notification.EXTRA_TITLE))
        add("titleBig", extras?.getCharSequence(Notification.EXTRA_TITLE_BIG))
        add("text", extras?.getCharSequence(Notification.EXTRA_TEXT))
        add("bigText", extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add("subText", extras?.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add("summary", extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEachIndexed { index, line ->
            add("line$index", line)
        }
        add("ticker", notification.tickerText)
        parts.add("category=${notification.category ?: ""}")
        parts.add("ongoing=${(notification.flags and Notification.FLAG_ONGOING_EVENT) != 0}")
        parts.add("smallIcon=${describeIcon(notification.smallIcon)}")
        parts.add("largeIcon=${describeIcon(notification.getLargeIcon())}")
        return parts.joinToString(" | ")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!receiverRegistered) {
            registerReceiver(streamingStateReceiver, IntentFilter(ACTION_STREAMING_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        if (isStreamingActive()) {
            bindStreamingService()
            syncActiveGoogleMapsNotification()
        }
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(streamingStateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        unbindStreamingService()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (!ensureStreamingServiceForActiveSession()) return

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()
        val googleMapsMode = isGoogleMapsModeEnabled()

        if (googleMapsMode && sbn.packageName == GOOGLE_MAPS_PACKAGE) {
            val parsed = GoogleMapsNotificationParser.parse(notification)
                ?: GoogleMapsNotificationParser.parse(title, text, bigText, subText)
            val iconManeuver = maneuverFromIcon(notification.smallIcon)
                ?: maneuverFromIcon(notification.getLargeIcon())
            val arrowIconData = extractArrowIconData(notification)
            val debugPrefix = describeGoogleMapsNotification(notification)
            if (parsed != null) {
                val finalManeuver = chooseManeuver(parsed.maneuver, iconManeuver)
                saveGoogleMapsDebug(
                    "parsedInstruction=${parsed.instruction} | parsedManeuver=${parsed.maneuver} | iconManeuver=${iconManeuver ?: ""} | finalManeuver=$finalManeuver | hasArrowIcon=${!arrowIconData.isNullOrBlank()} | parsedDistance=${parsed.distanceMeters.toInt()} | $debugPrefix"
                )
                Log.i(TAG, "Google Maps nav: ${parsed.instruction} (${parsed.distanceMeters.toInt()}m, $finalManeuver)")
                streamingService?.updateExternalNavigationStep(
                    parsed.instruction,
                    finalManeuver,
                    parsed.distanceMeters,
                    arrowIconData
                )
            } else {
                saveGoogleMapsDebug("parseFailed | iconManeuver=${iconManeuver ?: ""} | $debugPrefix")
                Log.w(TAG, "Google Maps nav parse failed: $debugPrefix")
                streamingService?.sendNotification(title, text ?: bigText ?: subText, sbn.packageName)
            }
            return
        }

        if (!getSharedPreferences("rokid_hud_prefs", Context.MODE_PRIVATE).getBoolean("stream_notifications", true)) return

        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        Log.d(TAG, "Notification from ${sbn.packageName}: $title / $text")
        streamingService?.sendNotification(title, text, sbn.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isStreamingActive()) return
        if (isGoogleMapsModeEnabled() && sbn.packageName == GOOGLE_MAPS_PACKAGE) {
            streamingService?.clearExternalNavigationStep()
            saveGoogleMapsDebug("notificationRemoved")
            Log.i(TAG, "Google Maps nav notification removed")
        }
    }
}
