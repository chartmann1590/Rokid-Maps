package com.rokid.hud.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class DeviceScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeviceScan"
        private const val RC_SCAN = 300
        private const val BLE_SCAN_DURATION_MS = 12000L
        private val ROKID_SERVICE_UUID = ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "rokid_glasses"
        private const val KEY_GLASSES_MAC = "glasses_mac"
        private const val KEY_GLASSES_NAME = "glasses_name"
    }

    private lateinit var btnBleScan: Button
    private lateinit var btnClassicScan: Button
    private lateinit var statusText: TextView
    private lateinit var rokidContainer: LinearLayout
    private lateinit var pairedContainer: LinearLayout
    private lateinit var nearbyContainer: LinearLayout
    private lateinit var connectedInfo: TextView

    private var btAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val discoveredBle = mutableSetOf<String>()
    private val discoveredClassic = mutableSetOf<String>()

    // ── BLE scan callback (finds Rokid glasses by UUID) ───────────────────

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val addr = device.address
            if (discoveredBle.contains(addr)) return
            discoveredBle.add(addr)
            val name = try { device.name } catch (_: Exception) { null }
            Log.i(TAG, "BLE found Rokid device: ${name ?: "unnamed"} ($addr)")
            runOnUiThread {
                addDeviceRow(rokidContainer, device, isRokid = true)
                statusText.text = "Found Rokid glasses: ${name ?: addr}"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            runOnUiThread { statusText.text = "BLE scan failed (error $errorCode)" }
        }
    }

    // ── Classic BT discovery receiver ─────────────────────────────────────

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && !discoveredClassic.contains(device.address)
                        && !discoveredBle.contains(device.address)) {
                        discoveredClassic.add(device.address)
                        addDeviceRow(nearbyContainer, device, isRokid = false)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    btnClassicScan.isEnabled = true
                    btnClassicScan.text = "Scan Other Devices"
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null) {
                            val name = try { device.name } catch (_: Exception) { null }
                            statusText.text = "Paired with ${name ?: device.address}"
                            saveGlassesInfo(device)
                            refreshPaired()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        bleScanner = btAdapter?.bluetoothLeScanner

        if (btAdapter == null) {
            statusText.text = "Bluetooth not available"
            btnBleScan.isEnabled = false
            btnClassicScan.isEnabled = false
            return
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(classicReceiver, filter)

        btnBleScan.setOnClickListener { startRokidBleScan() }
        btnClassicScan.setOnClickListener { startClassicScan() }

        refreshPaired()
        updateConnectedInfo()
    }

    override fun onDestroy() {
        stopAllScans()
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── BLE scan for Rokid glasses ────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startRokidBleScan() {
        if (!ensurePermissions()) return

        val scanner = bleScanner
        if (scanner == null) {
            statusText.text = "BLE not available"
            return
        }

        discoveredBle.clear()
        rokidContainer.removeAllViews()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ROKID_SERVICE_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, bleScanCallback)
        btnBleScan.isEnabled = false
        btnBleScan.text = "Scanning for Rokid..."
        statusText.text = "BLE scanning for Rokid glasses (UUID: 00009100...)..."

        handler.postDelayed({
            stopBleScan()
            btnBleScan.isEnabled = true
            btnBleScan.text = "Scan for Rokid Glasses"
            if (discoveredBle.isEmpty()) {
                statusText.text = "No Rokid glasses found — make sure they are in pairing mode"
            }
        }, BLE_SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        try { bleScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
    }

    // ── Classic BT discovery (fallback for other devices) ─────────────────

    @SuppressLint("MissingPermission")
    private fun startClassicScan() {
        if (!ensurePermissions()) return

        discoveredClassic.clear()
        nearbyContainer.removeAllViews()
        btAdapter?.cancelDiscovery()
        btAdapter?.startDiscovery()
        btnClassicScan.isEnabled = false
        btnClassicScan.text = "Scanning..."
        statusText.text = "Classic BT discovery running..."
    }

    @SuppressLint("MissingPermission")
    private fun stopAllScans() {
        stopBleScan()
        btAdapter?.cancelDiscovery()
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_SCAN)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == RC_SCAN && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            statusText.text = "Permissions granted — tap a scan button"
        }
    }

    // ── Device list management ────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun refreshPaired() {
        pairedContainer.removeAllViews()
        val bonded = btAdapter?.bondedDevices ?: return
        if (bonded.isEmpty()) {
            pairedContainer.addView(makeLabel("No paired devices", "#666666"))
        } else {
            for (d in bonded) addDeviceRow(pairedContainer, d, isRokid = false, alreadyBonded = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceRow(container: LinearLayout, device: BluetoothDevice,
                             isRokid: Boolean, alreadyBonded: Boolean = false) {
        val name = try { device.name } catch (_: Exception) { null }
        val label = name ?: "Unknown Device"
        val bonded = alreadyBonded || device.bondState == BluetoothDevice.BOND_BONDED
        val savedMac = getPrefs().getString(KEY_GLASSES_MAC, null)
        val isSelected = device.address == savedMac

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bgColor = if (isSelected) "#0A2A0A" else "#1E1E1E"
            setBackgroundColor(Color.parseColor(bgColor))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            isClickable = true; isFocusable = true
        }

        val prefix = when {
            isRokid -> "[ROKID] "
            else -> ""
        }
        val suffix = when {
            isSelected -> "  ★ Selected"
            bonded -> "  ✓ Paired"
            else -> ""
        }

        row.addView(TextView(this).apply {
            text = "$prefix$label"
            setTextColor(Color.parseColor(if (isRokid) "#00FF00" else "#CCCCCC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
        })
        row.addView(TextView(this).apply {
            text = "${device.address}$suffix"
            setTextColor(Color.parseColor(if (isSelected) "#00FF00" else "#888888"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
        })

        row.setOnClickListener {
            if (!bonded) {
                statusText.text = "Pairing with $label..."
                try { device.createBond() } catch (e: Exception) {
                    statusText.text = "Pairing failed: ${e.message}"
                }
            } else {
                saveGlassesInfo(device)
                statusText.text = "Selected $label as active glasses — start streaming to connect"
                updateConnectedInfo()
                refreshPaired()
            }
        }

        container.addView(row)
    }

    // ── Persist selected glasses ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun saveGlassesInfo(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null }
        getPrefs().edit()
            .putString(KEY_GLASSES_MAC, device.address)
            .putString(KEY_GLASSES_NAME, name ?: "Rokid Glasses")
            .apply()
    }

    private fun updateConnectedInfo() {
        val prefs = getPrefs()
        val mac = prefs.getString(KEY_GLASSES_MAC, null)
        val name = prefs.getString(KEY_GLASSES_NAME, null)
        connectedInfo.text = if (mac != null) "Active: $name ($mac)" else "No glasses selected"
        connectedInfo.visibility = View.VISIBLE
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── UI helpers ────────────────────────────────────────────────────────

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun makeLabel(text: String, color: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(6), 0, dp(6))
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun makeSectionHeader(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#00FF00"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(8))
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Connect Glasses"
            setTextColor(Color.parseColor("#00FF00"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(12))
        })

        connectedInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#88FF88"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(connectedInfo)

        btnBleScan = Button(this).apply {
            text = "Scan for Rokid Glasses"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#00FF00"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(btnBleScan)

        btnClassicScan = Button(this).apply {
            text = "Scan Other Devices"
            setTextColor(Color.parseColor("#00FF00"))
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(btnClassicScan)

        statusText = TextView(this).apply {
            text = "Tap 'Scan for Rokid Glasses' to find nearby glasses"
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(statusText)

        root.addView(makeSectionHeader("ROKID GLASSES (BLE)"))
        rokidContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(rokidContainer)

        root.addView(makeSectionHeader("PAIRED DEVICES"))
        pairedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(pairedContainer)

        root.addView(makeSectionHeader("NEARBY DEVICES"))
        nearbyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(nearbyContainer)

        root.addView(TextView(this).apply {
            text = "\nTip: Make sure your glasses are paired with this phone via Bluetooth settings. " +
                    "Then tap them in 'Paired Devices' to select as active glasses.\n\n" +
                    "Start Streaming on the main screen and the glasses app will auto-connect via Bluetooth."
            setTextColor(Color.parseColor("#555555"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(16), 0, dp(8))
        })

        scroll.addView(root)
        setContentView(scroll)
    }
}
