package com.rokid.hud.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.provider.Settings
import android.util.Log

class WifiConnector(private val context: Context) {

    companion object {
        private const val TAG = "WifiConn"
        private const val MAX_ENABLE_POLL = 15
        private const val POLL_INTERVAL_MS = 800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiMgr by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val connMgr by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var specifierCallback: ConnectivityManager.NetworkCallback? = null
    private var addedNetworkId: Int = -1
    private var currentSsid: String = ""

    @Volatile var connected = false; private set
    var onStatusChanged: ((Boolean) -> Unit)? = null

    fun connect(ssid: String, passphrase: String) {
        if (ssid.isBlank()) return
        if (ssid == currentSsid && connected) return

        Thread {
            try {
                disconnectInternal(disableWifi = false)
                currentSsid = ssid
                Log.i(TAG, "=== CONNECT START: SSID=$ssid ===")

                enableWifi()
                monitorAnyWifi()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectViaSpecifier(ssid, passphrase)
                }

                connectViaLegacy(ssid, passphrase)
                connectViaSuggestion(ssid, passphrase)

                for (i in 1..20) {
                    if (connected) break
                    Thread.sleep(1000)
                    Log.i(TAG, "Waiting for connection... attempt $i/20 wifi=${wifiMgr.isWifiEnabled}")
                }

                Log.i(TAG, "=== CONNECT END: connected=$connected ===")
            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}", e)
            }
        }.start()
    }

    fun disconnect() {
        Thread { disconnectInternal(disableWifi = true) }.start()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(disableWifi: Boolean) {
        Log.i(TAG, "=== DISCONNECT (disableWifi=$disableWifi) ===")
        updateConnected(false)
        currentSsid = ""

        specifierCallback?.let {
            try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
            specifierCallback = null
        }
        networkCallback?.let {
            try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
        try { connMgr.bindProcessToNetwork(null) } catch (_: Exception) {}

        removeLegacyNetwork()
        removeSuggestions()

        if (disableWifi) {
            disableWifi()
        }
    }

    // ── Wi-Fi radio ON/OFF ──────────────────────────────────────────────

    private fun enableWifi() {
        if (wifiMgr.isWifiEnabled) {
            Log.i(TAG, "Wi-Fi already enabled")
            return
        }
        Log.i(TAG, "Wi-Fi is OFF — enabling...")

        trySettingsGlobal(enable = true)
        trySetWifiEnabled(enable = true)

        for (i in 1..MAX_ENABLE_POLL) {
            if (wifiMgr.isWifiEnabled) {
                Log.i(TAG, "Wi-Fi enabled after ${i * POLL_INTERVAL_MS}ms")
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Wi-Fi still not enabled after polling — proceeding anyway (enabled=${wifiMgr.isWifiEnabled})")
    }

    private fun disableWifi() {
        if (!wifiMgr.isWifiEnabled) {
            Log.i(TAG, "Wi-Fi already disabled")
            return
        }
        Log.i(TAG, "Disabling Wi-Fi...")

        trySettingsGlobal(enable = false)
        trySetWifiEnabled(enable = false)

        for (i in 1..MAX_ENABLE_POLL) {
            if (!wifiMgr.isWifiEnabled) {
                Log.i(TAG, "Wi-Fi disabled after ${i * POLL_INTERVAL_MS}ms")
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "Wi-Fi still enabled after disable attempts")
    }

    private fun trySettingsGlobal(enable: Boolean) {
        val value = if (enable) 1 else 0
        try {
            val ok = Settings.Global.putInt(context.contentResolver, Settings.Global.WIFI_ON, value)
            Log.i(TAG, "Settings.Global.WIFI_ON=$value result=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "Settings.Global failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun trySetWifiEnabled(enable: Boolean) {
        try {
            val ok = wifiMgr.setWifiEnabled(enable)
            Log.i(TAG, "WifiManager.setWifiEnabled($enable) result=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "setWifiEnabled failed: ${e.message}")
        }

        try {
            val iface = Class.forName("android.net.wifi.IWifiManager")
            val serviceField = WifiManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            val service = serviceField.get(wifiMgr)
            val method = iface.getMethod("setWifiEnabled", String::class.java, Boolean::class.javaPrimitiveType)
            val ok = method.invoke(service, context.packageName, enable)
            Log.i(TAG, "IWifiManager.setWifiEnabled($enable) result=$ok")
        } catch (e: Exception) {
            Log.d(TAG, "Reflection setWifiEnabled failed: ${e.message}")
        }
    }

    // ── Connection methods ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectViaSpecifier(ssid: String, passphrase: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            specifierCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Specifier: network available — binding")
                    connMgr.bindProcessToNetwork(network)
                    updateConnected(true)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Specifier: network lost")
                    updateConnected(false)
                }

                override fun onUnavailable() {
                    Log.w(TAG, "Specifier: network unavailable (user denied or timeout)")
                }
            }

            connMgr.requestNetwork(request, specifierCallback!!)
            Log.i(TAG, "Specifier: requestNetwork sent for $ssid")
        } catch (e: Exception) {
            Log.w(TAG, "Specifier failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectViaLegacy(ssid: String, passphrase: String) {
        try {
            if (!wifiMgr.isWifiEnabled) return

            val existing = try {
                wifiMgr.configuredNetworks?.find {
                    it.SSID == "\"$ssid\"" || it.SSID == ssid
                }
            } catch (_: Exception) { null }

            if (existing != null && existing.networkId != -1) {
                wifiMgr.disconnect()
                val ok = wifiMgr.enableNetwork(existing.networkId, true)
                wifiMgr.reconnect()
                addedNetworkId = existing.networkId
                Log.i(TAG, "Legacy: reused existing netId=${existing.networkId} enable=$ok")
                return
            }

            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$passphrase\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            val netId = wifiMgr.addNetwork(conf)
            Log.i(TAG, "Legacy: addNetwork=$netId")
            if (netId != -1) {
                addedNetworkId = netId
                wifiMgr.disconnect()
                wifiMgr.enableNetwork(netId, true)
                wifiMgr.reconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectViaSuggestion(ssid: String, passphrase: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .setIsAppInteractionRequired(false)
                .build()
            wifiMgr.removeNetworkSuggestions(listOf(suggestion))
            val status = wifiMgr.addNetworkSuggestions(listOf(suggestion))
            Log.i(TAG, "Suggestion: status=$status (0=success)")
        } catch (e: Exception) {
            Log.w(TAG, "Suggestion: ${e.message}")
        }
    }

    // ── Monitor ─────────────────────────────────────────────────────────

    private fun monitorAnyWifi() {
        networkCallback?.let {
            try { connMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Monitor: Wi-Fi network available — binding process")
                connMgr.bindProcessToNetwork(network)
                updateConnected(true)
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "Monitor: Wi-Fi network lost")
                connMgr.bindProcessToNetwork(null)
                updateConnected(false)
            }
        }
        connMgr.registerNetworkCallback(request, networkCallback!!)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun updateConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        Log.i(TAG, "WiFi connected=$value")
        mainHandler.post { onStatusChanged?.invoke(value) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun removeLegacyNetwork() {
        if (addedNetworkId == -1) return
        try { wifiMgr.removeNetwork(addedNetworkId) } catch (_: Exception) {}
        addedNetworkId = -1
    }

    @SuppressLint("MissingPermission")
    private fun removeSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try { wifiMgr.removeNetworkSuggestions(emptyList()) } catch (_: Exception) {}
    }
}
