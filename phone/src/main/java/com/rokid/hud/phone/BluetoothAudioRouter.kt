package com.rokid.hud.phone

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

@SuppressLint("MissingPermission")
class BluetoothAudioRouter(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "BtAudioRouter"
        private const val PREFS_GLASSES = "rokid_glasses"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var glassesDevice: BluetoothDevice? = null

    var tts: TextToSpeech? = null; private set
    var ttsReady = false; private set
    var a2dpConnected = false; private set
    var scoConnected = false; private set

    private var lastSpokenInstruction = ""

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.i(TAG, "SCO state: $state")
            scoConnected = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
        }
    }

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = try { device?.name } catch (_: Exception) { "?" }
            Log.i(TAG, "A2DP state change: $state device=$name")
            a2dpConnected = (state == BluetoothProfile.STATE_CONNECTED)
        }
    }

    fun init() {
        tts = TextToSpeech(context, this)

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(scoReceiver, filter)
        context.registerReceiver(a2dpReceiver, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))

        resolveGlassesDevice()
        requestBluetoothProfiles()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "TTS ready=$ttsReady")

            tts?.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
        } else {
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    fun connectAudio() {
        resolveGlassesDevice()
        val device = glassesDevice
        if (device == null) {
            Log.w(TAG, "No glasses device resolved — cannot connect audio")
            return
        }

        connectA2dp(device)
        connectHeadset(device)

        handler.postDelayed({
            if (!a2dpConnected) {
                Log.i(TAG, "A2DP not connected, trying SCO fallback")
                startSco()
            }
        }, 3000)
    }

    fun speak(instruction: String, distance: Double, useImperial: Boolean) {
        if (!ttsReady) return
        if (instruction == lastSpokenInstruction) return
        lastSpokenInstruction = instruction

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

        ensureAudioRouted()
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "nav_step")
    }

    fun release() {
        try { context.unregisterReceiver(scoReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(a2dpReceiver) } catch (_: Exception) {}

        stopSco()

        val adapter = BluetoothAdapter.getDefaultAdapter()
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy = null
        headsetProxy = null

        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ── Internal ──────────────────────────────────────────────

    private fun resolveGlassesDevice() {
        val prefs = context.getSharedPreferences(PREFS_GLASSES, Context.MODE_PRIVATE)
        val addr = prefs.getString("glasses_address", null)
        if (addr == null) {
            Log.w(TAG, "No saved glasses address")
            return
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            glassesDevice = adapter.getRemoteDevice(addr)
            Log.i(TAG, "Glasses device: ${glassesDevice?.name} ($addr)")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve device $addr: ${e.message}")
        }
    }

    private fun requestBluetoothProfiles() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.A2DP -> {
                        a2dpProxy = proxy as BluetoothA2dp
                        Log.i(TAG, "A2DP proxy connected")
                        checkA2dpState()
                    }
                    BluetoothProfile.HEADSET -> {
                        headsetProxy = proxy as BluetoothHeadset
                        Log.i(TAG, "Headset proxy connected")
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.A2DP -> { a2dpProxy = null; a2dpConnected = false }
                    BluetoothProfile.HEADSET -> { headsetProxy = null }
                }
            }
        }, BluetoothProfile.A2DP)

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProxy = proxy as BluetoothHeadset
                    Log.i(TAG, "Headset proxy connected")
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) headsetProxy = null
            }
        }, BluetoothProfile.HEADSET)
    }

    private fun checkA2dpState() {
        val proxy = a2dpProxy ?: return
        val device = glassesDevice ?: return
        val state = proxy.getConnectionState(device)
        a2dpConnected = (state == BluetoothProfile.STATE_CONNECTED)
        Log.i(TAG, "A2DP current state for ${device.name}: $state (connected=$a2dpConnected)")
        if (!a2dpConnected) {
            connectA2dp(device)
        }
    }

    private fun connectA2dp(device: BluetoothDevice) {
        val proxy = a2dpProxy ?: return
        try {
            val connect = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = connect.invoke(proxy, device)
            Log.i(TAG, "A2DP connect(${device.name}): result=$result")
        } catch (e: Exception) {
            Log.w(TAG, "A2DP connect via reflection failed: ${e.message}")
        }
    }

    private fun connectHeadset(device: BluetoothDevice) {
        val proxy = headsetProxy ?: return
        try {
            val connect = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = connect.invoke(proxy, device)
            Log.i(TAG, "Headset connect(${device.name}): result=$result")
        } catch (e: Exception) {
            Log.w(TAG, "Headset connect via reflection failed: ${e.message}")
        }
    }

    private fun ensureAudioRouted() {
        if (a2dpConnected) {
            Log.d(TAG, "Audio via A2DP")
            return
        }

        if (!scoConnected && audioManager.isBluetoothScoAvailableOffCall) {
            Log.i(TAG, "Starting SCO for TTS")
            startSco()
        }
    }

    private fun startSco() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.i(TAG, "SCO started")
        } catch (e: Exception) {
            Log.w(TAG, "SCO start failed: ${e.message}")
        }
    }

    private fun stopSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "SCO stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCO stop failed: ${e.message}")
        }
    }
}
