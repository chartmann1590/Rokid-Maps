package com.rokid.hud.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil

/**
 * Wraps the Rokid CXR-M SDK for glasses Bluetooth connection management.
 * Handles: BLE init → get socketUuid/mac → connect via classic BT socket.
 */
class RokidConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "RokidConn"
        private const val PREFS_NAME = "rokid_glasses"
        private const val KEY_SOCKET_UUID = "cxr_socket_uuid"
        private const val KEY_MAC = "cxr_mac_address"
        private const val KEY_NAME = "glasses_name"
    }

    enum class State { DISCONNECTED, PAIRING, CONNECTING, CONNECTED, FAILED }

    var state: State = State.DISCONNECTED; private set
    var lastError: String = ""; private set
    var onStateChanged: ((State) -> Unit)? = null

    private val cxrApi: CxrApi get() = CxrApi.getInstance()
    private var socketUuid: String? = null
    private var macAddress: String? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val btCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?, macAddress: String?,
            rokidAccount: String?, glassesType: Int
        ) {
            Log.i(TAG, "Pairing success: uuid=$socketUuid mac=$macAddress type=$glassesType")
            if (socketUuid != null && macAddress != null) {
                this@RokidConnectionManager.socketUuid = socketUuid
                this@RokidConnectionManager.macAddress = macAddress
                savePairingInfo(socketUuid, macAddress)
                connectWithInfo(socketUuid, macAddress)
            } else {
                updateState(State.FAILED, "Invalid pairing info from glasses")
            }
        }

        override fun onConnected() {
            Log.i(TAG, "Connected to glasses via CXR SDK")
            updateState(State.CONNECTED)
        }

        override fun onDisconnected() {
            Log.i(TAG, "Disconnected from glasses")
            updateState(State.DISCONNECTED)
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            val msg = when (errorCode) {
                ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> "Invalid parameters"
                ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> "BLE connection failed"
                ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> "Socket connection failed"
                else -> "Connection failed: $errorCode"
            }
            Log.e(TAG, msg)
            updateState(State.FAILED, msg)
        }
    }

    /**
     * Pair with a new Rokid device discovered via BLE scan.
     * This calls CxrApi.initBluetooth which handles the Rokid-specific pairing handshake.
     */
    @SuppressLint("MissingPermission")
    fun pairDevice(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null }
        Log.i(TAG, "Initiating CXR pairing with ${name ?: device.address}")
        updateState(State.PAIRING)

        try {
            cxrApi.deinitBluetooth()
        } catch (_: Exception) {}

        try {
            cxrApi.initBluetooth(context, device, btCallback)
            prefs.edit().putString(KEY_NAME, name ?: "Rokid Glasses").apply()
        } catch (e: Exception) {
            Log.e(TAG, "initBluetooth failed", e)
            updateState(State.FAILED, e.message ?: "Init failed")
        }
    }

    /**
     * Reconnect to previously paired glasses using saved socketUuid + mac.
     */
    fun reconnect(): Boolean {
        val uuid = prefs.getString(KEY_SOCKET_UUID, null)
        val mac = prefs.getString(KEY_MAC, null)
        if (uuid == null || mac == null) {
            Log.w(TAG, "No saved pairing info — need to pair first")
            return false
        }
        connectWithInfo(uuid, mac)
        return true
    }

    private fun connectWithInfo(uuid: String, mac: String) {
        Log.i(TAG, "Connecting: uuid=$uuid mac=$mac")
        updateState(State.CONNECTING)
        try {
            val clientSecret = BuildConfig.ROKID_CLIENT_SECRET
            cxrApi.connectBluetooth(context, uuid, mac, btCallback, null, clientSecret)
        } catch (e: Exception) {
            Log.e(TAG, "connectBluetooth failed", e)
            updateState(State.FAILED, e.message ?: "Connect failed")
        }
    }

    fun disconnect() {
        try {
            cxrApi.deinitBluetooth()
        } catch (_: Exception) {}
        updateState(State.DISCONNECTED)
    }

    fun isConnected(): Boolean = try { cxrApi.isBluetoothConnected } catch (_: Exception) { false }

    fun getSavedGlassesName(): String? = prefs.getString(KEY_NAME, null)
    fun hasSavedPairing(): Boolean = prefs.getString(KEY_SOCKET_UUID, null) != null

    private fun savePairingInfo(uuid: String, mac: String) {
        prefs.edit()
            .putString(KEY_SOCKET_UUID, uuid)
            .putString(KEY_MAC, mac)
            .apply()
    }

    private fun updateState(newState: State, error: String = "") {
        state = newState
        lastError = error
        onStateChanged?.invoke(newState)
    }
}
