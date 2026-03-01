package com.rokid.hud.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Binder
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.rokid.hud.shared.protocol.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class HudStreamingService : Service() {

    companion object {
        private const val TAG = "HudStreaming"
        private const val SERVICE_NAME = "RokidHudSPP"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NOTIFICATION_ID = 1
        private const val LOCATION_INTERVAL_MS = 1000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): HudStreamingService = this@HudStreamingService
    }

    private val binder = LocalBinder()
    private var serverSocket: BluetoothServerSocket? = null
    private val clients = CopyOnWriteArrayList<BufferedWriter>()
    private val clientSessions = CopyOnWriteArrayList<ClientSession>()
    private val tileExecutor = Executors.newFixedThreadPool(4)
    private val TILE_URLS = arrayOf(
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png",
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png",
        "https://tile.openstreetmap.org/%d/%d/%d.png"
    )
    private val USER_AGENT = "RokidHudMaps/1.0 (Phone proxy)"

    private data class ClientSession(val socket: BluetoothSocket, val writer: BufferedWriter)
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    @Volatile private var running = false

    private var lastLat = 0.0
    private var lastLng = 0.0

    private var cachedSettings: SettingsMessage? = null
    private var cachedWifiCreds: WifiCredsMessage? = null

    private var wakeLock: PowerManager.WakeLock? = null

    var navigationManager: NavigationManager? = null
    var uiCallback: NavigationCallback? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            acquireWakeLock()
            initNavigation()
            startBluetoothServer()
            startLocationUpdates()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RokidHudMaps::Streaming"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 10 hours max; released in onDestroy
        }
        Log.i(TAG, "WakeLock acquired so maps keep updating when screen is off")
    }

    override fun onDestroy() {
        running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        navigationManager?.stopNavigation()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        try { serverSocket?.close() } catch (_: Exception) {}
        for (s in clientSessions) {
            try { s.socket.close() } catch (_: Exception) {}
        }
        clientSessions.clear()
        for (w in clients) { try { w.close() } catch (_: Exception) {} }
        clients.clear()
        tileExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun initNavigation() {
        navigationManager = NavigationManager(object : NavigationCallback {
            override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double) {
                sendRoute(waypoints, totalDistance, totalDuration)
                uiCallback?.onRouteCalculated(waypoints, totalDistance, totalDuration)
            }
            override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
                sendStep(instruction, maneuver, distance)
                uiCallback?.onStepChanged(instruction, maneuver, distance)
            }
            override fun onNavigationError(message: String) {
                uiCallback?.onNavigationError(message)
            }
            override fun onArrived() {
                sendStep("You have arrived!", "arrive", 0.0)
                uiCallback?.onArrived()
            }
            override fun onRerouting() {
                uiCallback?.onRerouting()
            }
        })
    }

    fun startNavigation(destLat: Double, destLng: Double) {
        navigationManager?.startNavigation(destLat, destLng, lastLat, lastLng)
    }

    fun stopNavigation() {
        navigationManager?.stopNavigation()
        sendStep("", "", 0.0)
        sendRoute(emptyList(), 0.0, 0.0)
    }

    fun getLastLocation(): Pair<Double, Double> = Pair(lastLat, lastLng)

    fun sendSettings(ttsEnabled: Boolean, useImperial: Boolean = false) {
        val msg = SettingsMessage(ttsEnabled, useImperial)
        cachedSettings = msg
        broadcast(ProtocolCodec.encodeSettings(msg))
    }

    fun sendWifiCreds(ssid: String, passphrase: String, enabled: Boolean) {
        val msg = WifiCredsMessage(ssid, passphrase, enabled)
        cachedWifiCreds = msg
        broadcast(ProtocolCodec.encodeWifiCreds(msg))
    }

    private val apkHandler = Handler(android.os.Looper.getMainLooper())

    /** Send an APK file to connected glasses in chunks over Bluetooth. Callbacks run on main thread. */
    fun sendApkToGlasses(
        uri: Uri,
        onProgress: ((sentChunks: Int, totalChunks: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (clients.isEmpty()) {
            apkHandler.post { onError?.invoke("No glasses connected") ?: Unit }
            return
        }
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open APK file")
                val totalSize = pfd.statSize
                pfd.close()
                if (totalSize <= 0) throw IllegalStateException("Invalid APK size")
                val CHUNK_RAW = 3072
                val totalChunks = ((totalSize + CHUNK_RAW - 1) / CHUNK_RAW).toInt()
                broadcast(ProtocolCodec.encodeApkStart(ApkStartMessage(totalSize, totalChunks)))
                apkHandler.post { onProgress?.invoke(0, totalChunks) }
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_RAW)
                    var sent = 0
                    var index = 0
                    while (running && index < totalChunks) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read < buffer.size) buffer.copyOf(read) else buffer
                        val b64 = Base64.getEncoder().encodeToString(chunk)
                        broadcast(ProtocolCodec.encodeApkChunk(ApkChunkMessage(index, b64)))
                        sent++
                        index++
                        if (sent % 20 == 0 || index == totalChunks) {
                            val s = sent
                            val t = totalChunks
                            apkHandler.post { onProgress?.invoke(s, t) }
                        }
                    }
                    broadcast(ProtocolCodec.encodeApkEnd())
                    apkHandler.post {
                        onProgress?.invoke(totalChunks, totalChunks)
                        onDone?.invoke()
                    }
                } ?: throw IllegalStateException("Cannot read APK")
            } catch (e: Exception) {
                Log.w(TAG, "sendApkToGlasses failed", e)
                apkHandler.post { onError?.invoke(e.message ?: "Unknown error") ?: Unit }
            }
        }.start()
    }

    fun sendNotification(title: String?, text: String?, packageName: String?) {
        broadcast(ProtocolCodec.encodeNotification(
            NotificationMessage(title, text, packageName, System.currentTimeMillis())
        ))
    }

    fun sendStep(instruction: String, maneuver: String, distance: Double) {
        broadcast(ProtocolCodec.encodeStep(StepMessage(instruction, maneuver, distance)))
    }

    fun sendRoute(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double) {
        broadcast(ProtocolCodec.encodeRoute(RouteMessage(waypoints, totalDistance, totalDuration)))
    }

    private fun resendCachedState(writer: BufferedWriter) {
        try {
            cachedSettings?.let {
                writer.write(ProtocolCodec.encodeSettings(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent settings to new client")
            }
            cachedWifiCreds?.let {
                writer.write(ProtocolCodec.encodeWifiCreds(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent wifi creds to new client")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-send cached state: ${e.message}")
        }
    }

    private fun broadcast(json: String) {
        val dead = mutableListOf<BufferedWriter>()
        for (writer in clients) {
            try {
                writer.write(json)
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Client write failed", e)
                dead.add(writer)
            }
        }
        clients.removeAll(dead.toSet())
    }

    private fun sendToClient(writer: BufferedWriter, json: String) {
        try {
            writer.write(json)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Send to client failed: ${e.message}")
        }
    }

    private fun handleTileRequest(z: Int, x: Int, y: Int, id: String, writer: BufferedWriter) {
        tileExecutor.execute {
            var data: String? = null
            for (template in TILE_URLS) {
                try {
                    val url = URL(String.format(template, z, x, y))
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.readBytes()
                        conn.disconnect()
                        data = Base64.getEncoder().encodeToString(bytes)
                        break
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Tile fetch $id failed: ${e.message}")
                }
            }
            val resp = ProtocolCodec.encodeTileResp(TileResponseMessage(id = id, data = data))
            sendToClient(writer, resp)
        }
    }

    private fun runClientReader(session: ClientSession) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    val parsed = ProtocolCodec.decode(line)
                    when (parsed) {
                        is ParsedMessage.TileReq -> handleTileRequest(
                            parsed.msg.z, parsed.msg.x, parsed.msg.y, parsed.msg.id, session.writer
                        )
                        else -> { /* ignore other inbound */ }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Client reader ended: ${e.message}")
            }
            try { session.socket.close() } catch (_: Exception) {}
            clientSessions.remove(session)
            clients.remove(session.writer)
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        fun acceptLoop(socket: BluetoothServerSocket, label: String) {
            Thread {
                Log.i(TAG, "$label SPP server listening on UUID $SPP_UUID")
                while (running) {
                    try {
                        val client: BluetoothSocket = socket.accept()
                        val addr = try { client.remoteDevice.address } catch (_: Exception) { "unknown" }
                        Log.i(TAG, "$label client connected: $addr")
                        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                        val session = ClientSession(client, writer)
                        clientSessions.add(session)
                        clients.add(writer)
                        resendCachedState(writer)
                        runClientReader(session)
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "$label accept failed: ${e.message}")
                    }
                }
            }.start()
        }

        try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            acceptLoop(serverSocket!!, "Insecure")
        } catch (e: Exception) {
            Log.e(TAG, "Insecure server failed: ${e.message}")
        }

        try {
            val secureSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME + "_S", SPP_UUID)
            acceptLoop(secureSocket, "Secure")
        } catch (e: Exception) {
            Log.w(TAG, "Secure server failed (insecure already running): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationUpdate(it) }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient!!.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        }
    }

    private fun onLocationUpdate(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        broadcast(ProtocolCodec.encodeState(
            StateMessage(loc.latitude, loc.longitude, loc.bearing, loc.speed, loc.accuracy)
        ))
        navigationManager?.onLocationUpdate(loc.latitude, loc.longitude)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HudApplication.CHANNEL_ID)
            .setContentTitle("Rokid HUD Active")
            .setContentText("Streaming to glasses")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
