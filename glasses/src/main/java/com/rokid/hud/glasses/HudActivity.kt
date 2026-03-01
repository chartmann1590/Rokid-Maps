package com.rokid.hud.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class HudActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val RC_BT = 200
        private const val TAG = "HudActivity"
    }

    private lateinit var hudView: HudView
    private lateinit var btClient: BluetoothClient
    private lateinit var tileManager: TileManager
    private lateinit var wifiConnector: WifiConnector

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenInstruction = ""

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else -1
            hudView.state = hudView.state.copy(batteryLevel = pct)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud)
        goFullscreen()

        hudView = findViewById(R.id.hudView)

        tileManager = TileManager { hudView.postInvalidate() }
        hudView.tileManager = tileManager

        hudView.onLayoutToggle = { toggleLayout() }

        tts = TextToSpeech(this, this)
        wifiConnector = WifiConnector(applicationContext)
        wifiConnector.onStatusChanged = { wifiConnected ->
            Log.i(TAG, "Wi-Fi status changed: connected=$wifiConnected")
            runOnUiThread {
                hudView.state = hudView.state.copy(wifiConnected = wifiConnected)
            }
        }

        btClient = BluetoothClient(
            context = this,
            onStateUpdate = { newState ->
                runOnUiThread {
                    hudView.state = newState
                    tileManager.onTileRequestViaProxy = if (newState.btConnected) {
                        { z, x, y, id -> btClient.sendTileRequest(z, x, y, id) }
                    } else null
                    if (newState.ttsEnabled && ttsReady
                        && newState.instruction.isNotBlank()
                        && newState.instruction != lastSpokenInstruction
                    ) {
                        lastSpokenInstruction = newState.instruction
                        speakInstruction(newState.instruction, newState.stepDistance, newState.useImperial)
                    }
                }
            },
            onWifiCreds = { ssid, pass, enabled ->
                if (enabled && ssid.isNotBlank()) {
                    Log.i(TAG, "Auto-connecting to Wi-Fi: $ssid")
                    wifiConnector.connect(ssid, pass)
                } else {
                    Log.i(TAG, "Disconnecting shared Wi-Fi")
                    wifiConnector.disconnect()
                }
            }
        )
        btClient.onTileReceived = { id, data -> tileManager.deliverTile(id, data) }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        requestPermissionsAndStartBt()
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        btClient.stop()
        wifiConnector.disconnect()
        tileManager.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady) {
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.w(TAG, "TTS language not supported")
            }
        } else {
            Log.w(TAG, "TTS init failed with status $status")
        }
    }

    private fun speakInstruction(instruction: String, distance: Double, useImperial: Boolean) {
        if (!ttsReady) return
        val distText = if (useImperial) {
            val feet = distance * 3.28084
            val miles = distance / 1609.344
            when {
                miles >= 0.1 -> String.format("in %.1f miles", miles)
                feet >= 50 -> "in ${feet.toInt()} feet"
                distance > 0 -> "now"
                else -> ""
            }
        } else {
            when {
                distance >= 1000 -> String.format("in %.1f kilometers", distance / 1000)
                distance >= 50 -> "in ${distance.toInt()} meters"
                distance > 0 -> "now"
                else -> ""
            }
        }
        val speech = if (distText.isNotBlank()) "$instruction, $distText" else instruction
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "nav_step")
    }

    private fun toggleLayout() {
        hudView.state = hudView.state.toggleLayout()
    }

    private fun installReceivedApk(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Install intent started for received APK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer: ${e.message}")
        }
    }

    private fun requestPermissionsAndStartBt() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CHANGE_WIFI_STATE)
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_BT)
        }
        // Always start BT regardless of permission results -- on Rokid glasses
        // standard permissions may not apply or dialogs may not show
        btClient.start()
        Log.i(TAG, "Bluetooth client started (permissions pending: ${needed.size})")
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        Log.i(TAG, "Permissions result: rc=$rc granted=${results.count { it == PackageManager.PERMISSION_GRANTED }}/${results.size}")
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView?.let { decor ->
                    decor.windowInsetsController?.let { ctrl ->
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } catch (_: Exception) {}
    }
}
