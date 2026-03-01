package com.rokid.hud.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class HudNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "HudNotifListener"
    }

    private var streamingService: HudStreamingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            streamingService = (service as HudStreamingService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val intent = Intent(this, HudStreamingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        if (!getSharedPreferences("rokid_hud_prefs", Context.MODE_PRIVATE).getBoolean("stream_notifications", true)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()

        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        Log.d(TAG, "Notification from ${sbn.packageName}: $title / $text")
        streamingService?.sendNotification(title, text, sbn.packageName)
    }
}
