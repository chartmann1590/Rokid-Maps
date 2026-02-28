package com.rokid.hud.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * Manages a Wi-Fi Direct group so the phone acts as a group owner (hotspot).
 * Connected peers (Rokid glasses) get internet through the phone's mobile data.
 */
class WifiShareManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiShare"
        private const val PREFS_NAME = "wifi_share"
        private const val KEY_ENABLED = "enabled"
    }

    enum class State { OFF, CREATING, ACTIVE, FAILED }

    var state: State = State.OFF; private set
    var groupSsid: String = ""; private set
    var groupPassphrase: String = ""; private set
    var connectedClients: Int = 0; private set
    var lastError: String = ""; private set

    var onStateChanged: ((State) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                            WifiP2pInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    if (info?.groupFormed == true && info.isGroupOwner) {
                        Log.i(TAG, "Group formed, we are the owner")
                        refreshGroupInfo()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "This device P2P state changed")
                }
            }
        }
    }

    fun init() {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(context, Looper.getMainLooper(), null)

        if (manager == null || channel == null) {
            Log.e(TAG, "Wi-Fi Direct not supported on this device")
            return
        }

        registerReceiver()
    }

    fun release() {
        stopSharing()
        unregisterReceiver()
        channel = null
        manager = null
    }

    /**
     * Create a Wi-Fi Direct group with the phone as group owner.
     * Peers connecting to this group get internet via the phone's cellular data.
     */
    @SuppressLint("MissingPermission")
    fun startSharing() {
        val mgr = manager ?: return
        val ch = channel ?: return

        if (state == State.ACTIVE) {
            Log.d(TAG, "Already sharing")
            return
        }

        updateState(State.CREATING)
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct group created")
                refreshGroupInfo()
            }

            override fun onFailure(reason: Int) {
                val msg = reasonToString(reason)
                Log.e(TAG, "Failed to create group: $msg")
                if (reason == WifiP2pManager.BUSY) {
                    // Group may already exist — try to get info
                    refreshGroupInfo()
                } else {
                    updateState(State.FAILED, msg)
                }
            }
        })
    }

    /**
     * Tear down the Wi-Fi Direct group.
     */
    @SuppressLint("MissingPermission")
    fun stopSharing() {
        val mgr = manager ?: return
        val ch = channel ?: return

        prefs.edit().putBoolean(KEY_ENABLED, false).apply()

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct group removed")
                groupSsid = ""
                groupPassphrase = ""
                connectedClients = 0
                updateState(State.OFF)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Remove group failed: ${reasonToString(reason)}")
                groupSsid = ""
                groupPassphrase = ""
                connectedClients = 0
                updateState(State.OFF)
            }
        })
    }

    fun wasEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    @SuppressLint("MissingPermission")
    fun refreshGroupInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null) {
                groupSsid = group.networkName
                groupPassphrase = group.passphrase ?: ""
                connectedClients = group.clientList?.size ?: 0
                Log.i(TAG, "Group: SSID=$groupSsid pass=$groupPassphrase clients=$connectedClients")
                updateState(State.ACTIVE)
            } else {
                if (state == State.CREATING) {
                    updateState(State.FAILED, "Group creation did not complete")
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(p2pReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    private fun updateState(newState: State, error: String = "") {
        state = newState
        lastError = error
        onStateChanged?.invoke(newState)
    }

    private fun reasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "Internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P not supported"
        WifiP2pManager.BUSY -> "Framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests"
        else -> "Unknown ($reason)"
    }
}
