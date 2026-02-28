package com.rokid.hud.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.rokid.hud.shared.protocol.*
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

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
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    @Volatile private var running = false

    private var lastLat = 0.0
    private var lastLng = 0.0

    private var cachedSettings: SettingsMessage? = null
    private var cachedWifiCreds: WifiCredsMessage? = null

    var navigationManager: NavigationManager? = null
    var uiCallback: NavigationCallback? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            initNavigation()
            startBluetoothServer()
            startLocationUpdates()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        navigationManager?.stopNavigation()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        try { serverSocket?.close() } catch (_: Exception) {}
        for (w in clients) { try { w.close() } catch (_: Exception) {} }
        clients.clear()
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
                val json = ProtocolCodec.encodeSettings(it)
                writer.write(json); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent settings to new client")
            }
            cachedWifiCreds?.let {
                val json = ProtocolCodec.encodeWifiCreds(it)
                writer.write(json); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent wifi creds to new client (ssid=${it.ssid} enabled=${it.enabled})")
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
                        clients.add(writer)
                        resendCachedState(writer)
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
            fusedLocationClient!!.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
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
