package com.rokid.hud.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import com.rokid.hud.shared.protocol.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class BluetoothClient(
    private val context: Context,
    private val onStateUpdate: (HudState) -> Unit,
    private val onWifiCreds: ((String, String, Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "HudBtClient"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RECONNECT_DELAY_MS = 3000L
    }

    @Volatile private var running = false
    @Volatile private var currentState = HudState()
    @Volatile private var btConnected = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    var onTileReceived: ((id: String, data: String?) -> Unit)? = null
    var onApkReceived: ((File) -> Unit)? = null

    private var apkOutputStream: FileOutputStream? = null
    private var apkFile: File? = null

    fun start() {
        if (running) return
        running = true
        Thread { connectLoop() }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    fun getCurrentState(): HudState = currentState

    @SuppressLint("MissingPermission")
    private fun connectLoop() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        while (running) {
            adapter.cancelDiscovery()

            val devices = getBondedDevices(adapter)
            if (devices.isEmpty()) {
                Log.w(TAG, "No bonded devices found, retrying in ${RECONNECT_DELAY_MS}ms")
                Thread.sleep(RECONNECT_DELAY_MS)
                continue
            }

            var connected = false
            for (device in devices) {
                if (!running) break
                val name = try { device.name } catch (_: Exception) { "?" }
                Log.i(TAG, "Trying $name (${device.address})")

                val s = tryConnect(device)
                if (s != null) {
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
                    Log.i(TAG, "Connected to $name (${device.address})")
                    connected = true
                    btConnected = true
                    currentState = currentState.copy(btConnected = true)
                    onStateUpdate(currentState)
                    try {
                        readFromSocket(s)
                    } catch (_: Exception) {}
                    try { s.close() } catch (_: Exception) {}
                    writer = null
                    socket = null
                    btConnected = false
                    currentState = currentState.copy(btConnected = false)
                    onStateUpdate(currentState)
                    Log.i(TAG, "Disconnected from $name")
                }
            }

            if (!connected) {
                Log.i(TAG, "No device accepted connection, retrying in ${RECONNECT_DELAY_MS}ms")
            }

            if (running) Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        Log.i(TAG, "Found ${bonded.size} bonded device(s)")
        for (d in bonded) {
            val name = try { d.name } catch (_: Exception) { "?" }
            Log.d(TAG, "  Bonded: $name (${d.address}) class=${d.bluetoothClass?.majorDeviceClass}")
        }
        val phones = bonded.filter { d ->
            d.bluetoothClass?.majorDeviceClass == 0x200
        }
        return if (phones.isNotEmpty()) phones else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val methods = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        )
        val labels = listOf("insecure SPP", "secure SPP", "channel 1 fallback")
        for (i in methods.indices) {
            try {
                val s = methods[i]()
                s.connect()
                Log.i(TAG, "Connected via ${labels[i]}")
                return s
            } catch (e: Exception) {
                Log.w(TAG, "${labels[i]} failed: ${e.message}")
            }
        }
        return null
    }

    private fun readFromSocket(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        while (running) {
            val line = reader.readLine() ?: break
            processMessage(line)
        }
    }

    private fun processMessage(line: String) {
        when (val parsed = ProtocolCodec.decode(line)) {
            is ParsedMessage.State -> {
                currentState = currentState.copy(
                    latitude = parsed.msg.latitude,
                    longitude = parsed.msg.longitude,
                    bearing = parsed.msg.bearing,
                    speed = parsed.msg.speed,
                    accuracy = parsed.msg.accuracy
                )
            }
            is ParsedMessage.Route -> {
                currentState = currentState.copy(
                    waypoints = parsed.msg.waypoints,
                    totalDistance = parsed.msg.totalDistance,
                    totalDuration = parsed.msg.totalDuration
                )
            }
            is ParsedMessage.Step -> {
                currentState = currentState.copy(
                    instruction = parsed.msg.instruction,
                    maneuver = parsed.msg.maneuver,
                    stepDistance = parsed.msg.distance
                )
            }
            is ParsedMessage.Notification -> {
                currentState = currentState.withNotification(
                    NotificationItem(
                        title = parsed.msg.title,
                        text = parsed.msg.text,
                        packageName = parsed.msg.packageName,
                        timeMs = parsed.msg.timeMs
                    )
                )
            }
            is ParsedMessage.Settings -> {
                val layoutMode = if (parsed.msg.useMiniMap) {
                    when (parsed.msg.miniMapStyle) {
                        "split" -> MapLayoutMode.MINI_SPLIT
                        else -> MapLayoutMode.MINI_BOTTOM
                    }
                } else MapLayoutMode.FULL_SCREEN
                currentState = currentState.copy(
                    ttsEnabled = parsed.msg.ttsEnabled,
                    useImperial = parsed.msg.useImperial,
                    layoutMode = layoutMode
                )
            }
            is ParsedMessage.WifiCreds -> {
                Log.i(TAG, "Received Wi-Fi creds: ssid=${parsed.msg.ssid} enabled=${parsed.msg.enabled}")
                onWifiCreds?.invoke(parsed.msg.ssid, parsed.msg.passphrase, parsed.msg.enabled)
            }
            is ParsedMessage.TileResp ->
                onTileReceived?.invoke(parsed.msg.id, parsed.msg.data)
            is ParsedMessage.TileReq -> { /* we send these, don't expect to receive */ }
            is ParsedMessage.ApkStart -> {
                try {
                    apkOutputStream?.close()
                } catch (_: Exception) {}
                apkFile = File(context.cacheDir, "glasses_update.apk")
                apkOutputStream = FileOutputStream(apkFile)
                Log.i(TAG, "APK receive started, ${parsed.msg.totalChunks} chunks")
            }
            is ParsedMessage.ApkChunk -> {
                try {
                    val bytes = Base64.decode(parsed.msg.data, Base64.DEFAULT)
                    apkOutputStream?.write(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "APK chunk ${parsed.msg.index} failed: ${e.message}")
                }
            }
            is ParsedMessage.ApkEnd -> {
                try {
                    apkOutputStream?.close()
                } catch (_: Exception) {}
                apkOutputStream = null
                val file = apkFile
                apkFile = null
                if (file != null && file.exists()) {
                    Log.i(TAG, "APK receive complete: ${file.length()} bytes")
                    onApkReceived?.invoke(file)
                } else {
                    Log.w(TAG, "APK file missing after receive")
                }
            }
            is ParsedMessage.Unknown -> {
                Log.w(TAG, "Unknown message: $line")
            }
        }
        onStateUpdate(currentState)
    }

    fun sendTileRequest(z: Int, x: Int, y: Int, id: String) {
        val w = writer ?: return
        try {
            val json = ProtocolCodec.encodeTileReq(TileRequestMessage(id = id, z = z, x = x, y = y))
            synchronized(w) {
                w.write(json)
                w.newLine()
                w.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send tile req failed: ${e.message}")
        }
    }
}
