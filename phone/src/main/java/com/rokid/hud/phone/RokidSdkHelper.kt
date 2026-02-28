package com.rokid.hud.phone

import android.content.Context
import android.util.Log

/**
 * Wrapper for Rokid Mobile SDK initialization.
 *
 * When the Rokid AAR (com.rokid.mobile:sdk) is added to phone/libs/
 * and included in build.gradle.kts, replace the stub calls here with
 * actual SDK calls:
 *
 *   RokidMobileSDK.init(context, clientId, clientSecret, accessKey)
 *
 * Until then this helper logs the credentials and provides a
 * compile-safe bridge so the rest of the app can reference it.
 */
object RokidSdkHelper {

    private const val TAG = "RokidSDK"

    @Volatile
    var initialized = false
        private set

    fun init(context: Context) {
        val clientId = BuildConfig.ROKID_CLIENT_ID
        val clientSecret = BuildConfig.ROKID_CLIENT_SECRET
        val accessKey = BuildConfig.ROKID_ACCESS_KEY

        if (clientId.isBlank() || clientSecret.isBlank() || accessKey.isBlank()) {
            Log.w(TAG, "Rokid credentials not configured in local.properties - SDK disabled")
            return
        }

        Log.i(TAG, "Initializing Rokid SDK  clientId=${clientId.take(8)}...")

        // TODO: Replace with actual SDK init when AAR is available:
        // RokidMobileSDK.init(context.applicationContext, clientId, clientSecret, accessKey)
        // RokidMobileSDK.setDeviceType(DeviceType.GLASS)

        initialized = true
        Log.i(TAG, "Rokid SDK initialized (stub - replace with real SDK calls)")
    }
}
