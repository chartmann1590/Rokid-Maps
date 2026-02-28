package com.rokid.hud.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class HudApplication : Application() {

    companion object {
        const val TAG = "RokidHud"
        const val CHANNEL_ID = "hud_streaming"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initRokidCxrSdk()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HUD Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streams GPS and navigation data to Rokid glasses"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun initRokidCxrSdk() {
        val clientSecret = BuildConfig.ROKID_CLIENT_SECRET

        if (clientSecret.isBlank()) {
            Log.w(TAG, "Rokid credentials not configured — SDK features disabled")
            return
        }

        try {
            Class.forName("com.rokid.cxr.client.extend.CxrApi")
            Log.i(TAG, "Rokid CXR SDK loaded (client-m). Credentials ready.")
            Log.i(TAG, "  Client ID: ${BuildConfig.ROKID_CLIENT_ID.take(8)}...")
            Log.i(TAG, "  BLE scanning and SDK pairing available.")
        } catch (e: Throwable) {
            Log.w(TAG, "Rokid CXR SDK not available: ${e.message}")
        }
    }
}
