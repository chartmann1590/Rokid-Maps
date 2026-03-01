package com.rokid.hud.phone

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.hud.shared.protocol.Waypoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_PERMISSIONS = 100
        private const val RC_WIFI_PERM = 101
        private const val RC_PICK_APK = 102
        private const val PREF_TTS = "tts_enabled"
        private const val PREF_IMPERIAL = "use_imperial"
        private const val PREF_MINI_MAP = "use_mini_map"
        private const val PREF_MINI_MAP_STYLE = "mini_map_style"
        private const val PREF_STREAM_NOTIFICATIONS = "stream_notifications"
        private const val PREF_SHOW_FULL_ROUTE_STEPS = "show_full_route_steps"
        private const val PREFS_GLASSES = "rokid_glasses"
        private const val PREFS_HUD = "rokid_hud_prefs"
    }

    private lateinit var btAudioRouter: BluetoothAudioRouter

    // Header & status
    private lateinit var btnStart: Button
    private lateinit var glassesStatusDot: View
    private lateinit var glassesStatusText: TextView
    private lateinit var btnScanGlasses: Button
    private lateinit var btnUpdateGlassesApp: Button
    private lateinit var statusText: TextView

    // Navigate section
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnShowSaved: Button
    private lateinit var searchResults: ListView
    private lateinit var routeCard: LinearLayout
    private lateinit var routeDestText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var btnNavigate: Button
    private lateinit var btnSavePlace: Button

    // Live directions + map (shown only when navigating)
    private lateinit var navStatus: LinearLayout
    private lateinit var navMapView: MapView
    private lateinit var navInstructionText: TextView
    private lateinit var navDistanceText: TextView
    private lateinit var navFullStepsPanel: LinearLayout
    private lateinit var navFullStepsList: ListView
    private lateinit var switchShowFullRouteSteps: Switch
    private lateinit var btnStopNav: Button

    // Settings
    private lateinit var switchUnits: Switch
    private lateinit var switchTts: Switch
    private lateinit var switchMiniMap: Switch
    private lateinit var miniMapStyleGroup: RadioGroup
    private lateinit var radioStrip: RadioButton
    private lateinit var radioSplit: RadioButton
    private lateinit var switchWifiShare: Switch
    private lateinit var wifiShareStatus: TextView
    private lateinit var wifiInfoCard: LinearLayout
    private lateinit var wifiSsidText: TextView
    private lateinit var wifiPassText: TextView
    private lateinit var wifiClientsText: TextView
    private lateinit var hotspotSsidInput: EditText
    private lateinit var hotspotPassInput: EditText
    private lateinit var btnSendHotspotToGlasses: Button
    private lateinit var notifStatusText: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var switchStreamNotifications: Switch

    // Managers
    private lateinit var wifiShareManager: WifiShareManager
    private lateinit var savedPlacesManager: SavedPlacesManager

    // State
    private var service: HudStreamingService? = null
    private var bound = false
    private var streaming = false
    private var searchResultsList: List<SearchResult> = emptyList()
    private var savedPlacesList: List<SavedPlace> = emptyList()
    private var selectedDest: SearchResult? = null
    private var showingSaved = false
    private var currentRouteWaypoints: List<Waypoint> = emptyList()
    private var fullRouteSteps: List<NavigationStep> = emptyList()

    private val navMapHandler = Handler(Looper.getMainLooper())
    private val navMapUpdateRunnable = object : Runnable {
        override fun run() {
            if (!::navMapView.isInitialized || navStatus.visibility != View.VISIBLE) return
            val (lat, lng) = service?.getLastLocation() ?: return
            navMapView.controller.setCenter(GeoPoint(lat, lng))
            navMapView.controller.setZoom(17.0)
            navMapHandler.postDelayed(this, 2000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as HudStreamingService.LocalBinder).getService()
            bound = true
            streaming = true
            service?.uiCallback = navCallback
            sendCurrentSettings()
            updateStreamingUi()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false; streaming = false
            updateStreamingUi()
        }
    }

    private val navCallback = object : NavigationCallback {
        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
            runOnUiThread {
                currentRouteWaypoints = waypoints
                fullRouteSteps = steps
                routeInfoText.text = "${formatDist(totalDistance)}  ·  ${formatTime(totalDuration)}"
                showNavStatus()
                updateNavMap()
                updateFullStepsList()
            }
        }
        override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
            runOnUiThread {
                navInstructionText.text = instruction
                navDistanceText.text = formatDist(distance)
                speakNavInstruction(instruction, distance)
            }
        }
        override fun onNavigationError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Navigation error: $message", Toast.LENGTH_LONG).show()
                btnNavigate.isEnabled = true
                routeInfoText.text = "Route failed — try again"
            }
        }
        override fun onArrived() {
            runOnUiThread {
                navInstructionText.text = "You have arrived!"
                navDistanceText.text = ""
                speakNavInstruction("You have arrived!", 0.0)
                Toast.makeText(this@MainActivity, "Arrived at destination!", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onRerouting() {
            runOnUiThread {
                navInstructionText.text = "Recalculating route..."
                navDistanceText.text = ""
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        savedPlacesManager = SavedPlacesManager(this)
        btAudioRouter = BluetoothAudioRouter(applicationContext)
        btAudioRouter.init()

        bindViews()
        setupWifiManager()
        setupListeners()
        updateGlassesStatus()
        updateNotifStatus()
    }

    private fun bindViews() {
        btnStart = findViewById(R.id.btnStart)
        glassesStatusDot = findViewById(R.id.glassesStatusDot)
        glassesStatusText = findViewById(R.id.glassesStatusText)
        btnScanGlasses = findViewById(R.id.btnScanGlasses)
        btnUpdateGlassesApp = findViewById(R.id.btnUpdateGlassesApp)
        statusText = findViewById(R.id.statusText)

        searchInput = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)
        btnShowSaved = findViewById(R.id.btnShowSaved)
        searchResults = findViewById(R.id.searchResults)
        routeCard = findViewById(R.id.routeCard)
        routeDestText = findViewById(R.id.routeDestText)
        routeInfoText = findViewById(R.id.routeInfoText)
        btnNavigate = findViewById(R.id.btnNavigate)
        btnSavePlace = findViewById(R.id.btnSavePlace)

        navStatus = findViewById(R.id.navStatus)
        navMapView = findViewById(R.id.navMapView)
        navInstructionText = findViewById(R.id.navInstructionText)
        navDistanceText = findViewById(R.id.navDistanceText)
        navFullStepsPanel = findViewById(R.id.navFullStepsPanel)
        navFullStepsList = findViewById(R.id.navFullStepsList)
        switchShowFullRouteSteps = findViewById(R.id.switchShowFullRouteSteps)
        btnStopNav = findViewById(R.id.btnStopNav)
        initNavMap()

        switchUnits = findViewById(R.id.switchUnits)
        switchTts = findViewById(R.id.switchTts)
        switchMiniMap = findViewById(R.id.switchMiniMap)
        miniMapStyleGroup = findViewById(R.id.miniMapStyleGroup)
        radioStrip = findViewById(R.id.radioStrip)
        radioSplit = findViewById(R.id.radioSplit)
        switchWifiShare = findViewById(R.id.switchWifiShare)
        wifiShareStatus = findViewById(R.id.wifiShareStatus)
        wifiInfoCard = findViewById(R.id.wifiInfoCard)
        wifiSsidText = findViewById(R.id.wifiSsidText)
        wifiPassText = findViewById(R.id.wifiPassText)
        wifiClientsText = findViewById(R.id.wifiClientsText)
        hotspotSsidInput = findViewById(R.id.hotspotSsidInput)
        hotspotPassInput = findViewById(R.id.hotspotPassInput)
        btnSendHotspotToGlasses = findViewById(R.id.btnSendHotspotToGlasses)
        notifStatusText = findViewById(R.id.notifStatusText)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        switchStreamNotifications = findViewById(R.id.switchStreamNotifications)

        switchTts.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)
        switchUnits.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)
        switchMiniMap.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_MINI_MAP, false)
        val savedStyle = getPreferences(MODE_PRIVATE).getString(PREF_MINI_MAP_STYLE, "strip")
        if (savedStyle == "split") radioSplit.isChecked = true else radioStrip.isChecked = true
        miniMapStyleGroup.visibility = if (switchMiniMap.isChecked) View.VISIBLE else View.GONE
        switchStreamNotifications.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_STREAM_NOTIFICATIONS, true)
    }

    private fun setupWifiManager() {
        wifiShareManager = WifiShareManager(applicationContext)
        wifiShareManager.init()
        wifiShareManager.onStateChanged = { state -> runOnUiThread { updateWifiUi(state) } }
        switchWifiShare.isChecked = wifiShareManager.wasEnabled()
        if (wifiShareManager.wasEnabled()) {
            wifiShareManager.startSharing()
        }
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnScanGlasses.setOnClickListener {
            startActivity(Intent(this, DeviceScanActivity::class.java))
        }
        btnUpdateGlassesApp.setOnClickListener { openApkPicker() }

        btnSearch.setOnClickListener { performSearch() }
        searchInput.setOnEditorActionListener { _, _, _ -> performSearch(); true }
        btnShowSaved.setOnClickListener { toggleSavedPlaces() }
        searchResults.setOnItemClickListener { _, _, pos, _ -> onItemSelected(pos) }
        btnNavigate.setOnClickListener { startNavigation() }
        btnSavePlace.setOnClickListener { saveCurrentPlace() }
        btnStopNav.setOnClickListener { stopNavigation() }
        switchShowFullRouteSteps.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_FULL_ROUTE_STEPS, isChecked).apply()
            navFullStepsPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) updateFullStepsList()
            sendCurrentSettings()
        }
        btnNotifAccess.setOnClickListener { openNotificationListenerSettings() }

        switchTts.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_TTS, isChecked).apply()
            sendCurrentSettings()
        }

        switchUnits.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_IMPERIAL, isChecked).apply()
            sendCurrentSettings()
        }

        switchMiniMap.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_MINI_MAP, isChecked).apply()
            miniMapStyleGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            sendCurrentSettings()
        }

        miniMapStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = if (checkedId == R.id.radioSplit) "split" else "strip"
            getPreferences(MODE_PRIVATE).edit().putString(PREF_MINI_MAP_STYLE, style).apply()
            sendCurrentSettings()
        }

        switchStreamNotifications.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_STREAM_NOTIFICATIONS, isChecked).apply()
            sendCurrentSettings()
        }

        switchWifiShare.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkWifiPermissionsAndStart() else wifiShareManager.stopSharing()
        }

        btnSendHotspotToGlasses.setOnClickListener { sendHotspotToGlasses() }
    }

    private var apkProgressDialog: AlertDialog? = null

    private fun openApkPicker() {
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming and connect glasses first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/apk"))
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select glasses APK"), RC_PICK_APK)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), RC_PICK_APK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK_APK && resultCode == RESULT_OK && data?.data != null) {
            sendApkToGlasses(data.data!!)
        }
    }

    private fun sendApkToGlasses(uri: Uri) {
        apkProgressDialog = AlertDialog.Builder(this)
            .setTitle("Update glasses app")
            .setMessage("Sending APK... 0%")
            .setCancelable(false)
            .show()
        service!!.sendApkToGlasses(
            uri,
            onProgress = { sent, total ->
                val pct = if (total > 0) (100 * sent / total) else 0
                apkProgressDialog?.setMessage("Sending APK... $pct%")
            },
            onDone = {
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "APK sent. Open the glasses and confirm install when prompted.", Toast.LENGTH_LONG).show()
            },
            onError = { msg ->
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "Failed: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun sendHotspotToGlasses() {
        val ssid = hotspotSsidInput.text.toString().trim()
        val pass = hotspotPassInput.text.toString()
        if (ssid.isBlank()) {
            Toast.makeText(this, "Enter your hotspot name (SSID)", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first so glasses are connected", Toast.LENGTH_SHORT).show()
            return
        }
        service!!.sendWifiCreds(ssid, pass, true)
        Toast.makeText(this, "Sent to glasses — they will enable Wi‑Fi and connect for internet", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        navMapView.onResume()
        updateGlassesStatus()
        updateNotifStatus()
        if (bound) service?.uiCallback = navCallback
        if (streaming) btAudioRouter.connectAudio()
    }

    override fun onPause() {
        super.onPause()
        navMapView.onPause()
    }

    override fun onDestroy() {
        btAudioRouter.release()
        wifiShareManager.release()
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    // ── Glasses status ─────────────────────────────────────────────────────

    private fun updateGlassesStatus() {
        val prefs = getSharedPreferences(PREFS_GLASSES, MODE_PRIVATE)
        val savedName = prefs.getString("glasses_name", null)
        if (savedName != null) {
            glassesStatusText.text = "Paired: $savedName"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            btnScanGlasses.text = "Change"
        } else {
            glassesStatusText.text = "No glasses paired"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            btnScanGlasses.text = "Pair Glasses"
        }
    }

    private fun updateStreamingUi() {
        if (streaming) {
            btnStart.text = "Streaming"
            btnStart.isEnabled = false
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            statusText.text = "Streaming to glasses — search a destination"
        } else {
            btnStart.text = "Start Streaming"
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
            statusText.text = "Tap Start Streaming to begin"
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        hideKeyboard()
        showingSaved = false
        btnSearch.isEnabled = false
        statusText.text = "Searching..."

        Thread {
            try {
                val results = NominatimClient.search(query)
                runOnUiThread {
                    searchResultsList = results
                    if (results.isEmpty()) {
                        statusText.text = "No results found"
                        searchResults.visibility = View.GONE
                    } else {
                        setResultsList(results.map { it.displayName }, false)
                        searchResults.visibility = View.VISIBLE
                        adjustListHeight()
                        statusText.text = "${results.size} results"
                    }
                    btnSearch.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Search error: ${e.message}"
                    btnSearch.isEnabled = true
                }
            }
        }.start()
    }

    // ── Saved places ───────────────────────────────────────────────────────

    private fun toggleSavedPlaces() {
        if (showingSaved && searchResults.visibility == View.VISIBLE) {
            searchResults.visibility = View.GONE
            showingSaved = false
            btnShowSaved.text = "Saved Places"
            return
        }
        showingSaved = true
        savedPlacesList = savedPlacesManager.getAll()
        if (savedPlacesList.isEmpty()) {
            Toast.makeText(this, "No saved places yet", Toast.LENGTH_SHORT).show()
            searchResults.visibility = View.GONE
            return
        }
        setResultsList(savedPlacesList.map { it.name }, true)
        searchResults.visibility = View.VISIBLE
        adjustListHeight()
        btnShowSaved.text = "Hide Saved"
        statusText.text = "${savedPlacesList.size} saved place(s)"

        searchResults.setOnItemLongClickListener { _, _, pos, _ ->
            if (showingSaved && pos < savedPlacesList.size) {
                val place = savedPlacesList[pos]
                savedPlacesManager.delete(place)
                Toast.makeText(this, "Removed: ${place.name}", Toast.LENGTH_SHORT).show()
                toggleSavedPlaces()
                true
            } else false
        }
    }

    private fun saveCurrentPlace() {
        val dest = selectedDest ?: return
        val parts = dest.displayName.split(",").map { it.trim() }
        val shortName = if (parts.size >= 2) parts.take(3).joinToString(", ") else dest.displayName
        savedPlacesManager.save(SavedPlace(shortName, dest.lat, dest.lng))
        Toast.makeText(this, "Saved: $shortName", Toast.LENGTH_SHORT).show()
        btnSavePlace.text = "Saved!"
        btnSavePlace.isEnabled = false
    }

    private fun onItemSelected(position: Int) {
        if (showingSaved) {
            if (position >= savedPlacesList.size) return
            val place = savedPlacesList[position]
            selectedDest = SearchResult(place.name, place.lat, place.lng)
        } else {
            if (position >= searchResultsList.size) return
            selectedDest = searchResultsList[position]
        }
        searchResults.visibility = View.GONE

        val dest = selectedDest!!
        routeDestText.text = dest.displayName
        routeInfoText.text = "Tap Start Navigation to calculate route"
        routeCard.visibility = View.VISIBLE
        navStatus.visibility = View.GONE
        btnNavigate.isEnabled = true
        btnSavePlace.text = "Save"
        btnSavePlace.isEnabled = true
    }

    private fun setResultsList(items: List<String>, isSaved: Boolean) {
        searchResults.adapter = object : ArrayAdapter<String>(this, R.layout.item_search_result, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_search_result, parent, false)
                val icon = view.findViewById<TextView>(R.id.resultIcon)
                val text = view.findViewById<TextView>(android.R.id.text1)
                icon.text = if (isSaved) "\u2B50" else "\uD83D\uDCCD"
                text.text = getItem(position)
                return view
            }
        }
    }

    private fun adjustListHeight() {
        val count = searchResults.adapter?.count ?: 0
        val maxItems = minOf(count, 5)
        val itemH = (52 * resources.displayMetrics.density).toInt()
        val params = searchResults.layoutParams
        params.height = maxItems * itemH
        searchResults.layoutParams = params
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun startNavigation() {
        val dest = selectedDest ?: return
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        routeInfoText.text = "Calculating route..."
        btnNavigate.isEnabled = false
        service!!.startNavigation(dest.lat, dest.lng)
    }

    private fun showNavStatus() {
        navStatus.visibility = View.VISIBLE
        btnNavigate.isEnabled = true
        val showFullSteps = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.isChecked = showFullSteps
        navFullStepsPanel.visibility = if (showFullSteps) View.VISIBLE else View.GONE
        if (showFullSteps) updateFullStepsList()
        navMapHandler.postDelayed(navMapUpdateRunnable, 500L)
    }

    private fun stopNavigation() {
        navMapHandler.removeCallbacks(navMapUpdateRunnable)
        service?.stopNavigation()
        navStatus.visibility = View.GONE
        navInstructionText.text = ""
        navDistanceText.text = ""
        currentRouteWaypoints = emptyList()
        fullRouteSteps = emptyList()
    }

    private fun updateFullStepsList() {
        val items = fullRouteSteps.mapIndexed { i, step ->
            "${i + 1}. ${step.instruction} — ${formatDist(step.distance)}"
        }
        navFullStepsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun initNavMap() {
        navMapView.setTileSource(TileSourceFactory.MAPNIK)
        navMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        navMapView.setMultiTouchControls(true)
        navMapView.controller.setZoom(15.0)
    }

    private fun updateNavMap() {
        if (currentRouteWaypoints.isEmpty()) return
        navMapView.overlays.removeIf { it is Polyline }
        val line = Polyline().apply {
            outlinePaint.color = Color.parseColor("#00E676")
            outlinePaint.strokeWidth = 12f
            outlinePaint.isAntiAlias = true
            setPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        navMapView.overlays.add(line)
        val (lat, lng) = service?.getLastLocation() ?: run {
            val first = currentRouteWaypoints.first()
            Pair(first.latitude, first.longitude)
        }
        navMapView.controller.setCenter(GeoPoint(lat, lng))
        navMapView.controller.setZoom(17.0)
        val box = BoundingBox.fromGeoPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        navMapView.zoomToBoundingBox(box, false)
        navMapView.invalidate()
    }

    // ── Wi-Fi sharing ──────────────────────────────────────────────────────

    private fun updateWifiUi(state: WifiShareManager.State) {
        when (state) {
            WifiShareManager.State.OFF -> {
                wifiShareStatus.text = "Create Wi-Fi Direct hotspot for glasses"
                wifiInfoCard.visibility = View.GONE
                switchWifiShare.isChecked = false
                service?.sendWifiCreds("", "", false)
            }
            WifiShareManager.State.CREATING -> {
                wifiShareStatus.text = "Creating hotspot..."
                wifiInfoCard.visibility = View.GONE
            }
            WifiShareManager.State.ACTIVE -> {
                wifiShareStatus.text = "Hotspot active"
                wifiInfoCard.visibility = View.VISIBLE
                wifiSsidText.text = wifiShareManager.groupSsid
                wifiPassText.text = wifiShareManager.groupPassphrase
                val n = wifiShareManager.connectedClients
                wifiClientsText.text = if (n == 0) "Sending credentials to glasses..." else "$n device(s) connected"
                switchWifiShare.isChecked = true
                service?.sendWifiCreds(wifiShareManager.groupSsid, wifiShareManager.groupPassphrase, true)
            }
            WifiShareManager.State.FAILED -> {
                wifiShareStatus.text = "Failed: ${wifiShareManager.lastError}"
                wifiInfoCard.visibility = View.GONE
            }
        }
    }

    private fun checkWifiPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_WIFI_PERM)
        } else {
            wifiShareManager.startSharing()
        }
    }

    // ── Notification status ────────────────────────────────────────────────

    private fun updateNotifStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            notifStatusText.text = "Notifications forwarding to glasses"
            notifStatusText.setTextColor(0xFF66BB6A.toInt())
            btnNotifAccess.text = "Granted"
            btnNotifAccess.isEnabled = false
        } else {
            notifStatusText.text = "Show phone notifications on glasses"
            notifStatusText.setTextColor(0xFF757575.toInt())
            btnNotifAccess.text = "Grant"
            btnNotifAccess.isEnabled = true
        }
    }

    // ── Permissions & streaming ────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMISSIONS)
        } else {
            startStreaming()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        when (rc) {
            RC_PERMISSIONS -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) startStreaming()
            }
            RC_WIFI_PERM -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                    wifiShareManager.startSharing()
                } else {
                    switchWifiShare.isChecked = false
                    Toast.makeText(this, "Wi-Fi permissions required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startStreaming() {
        val intent = Intent(this, HudStreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        streaming = true
        updateStreamingUi()
        statusText.text = "Streaming started — search a destination"
        btAudioRouter.connectAudio()
        promptBatteryOptimizationIfNeeded()
    }

    private fun promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep running when screen is off")
            .setMessage("To keep maps and directions updating on your glasses when the phone screen turns off, allow this app to run in the background. Tap \"Allow\" below and turn off battery optimization for this app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val i = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── Misc ───────────────────────────────────────────────────────────────

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, HudNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun speakNavInstruction(instruction: String, distance: Double) {
        if (!getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)) return
        btAudioRouter.speak(instruction, distance, isImperial())
    }

    private fun sendCurrentSettings() {
        val prefs = getPreferences(MODE_PRIVATE)
        val hudPrefs = getSharedPreferences(PREFS_HUD, MODE_PRIVATE)
        service?.sendSettings(
            ttsEnabled = prefs.getBoolean(PREF_TTS, false),
            useImperial = prefs.getBoolean(PREF_IMPERIAL, false),
            useMiniMap = prefs.getBoolean(PREF_MINI_MAP, false),
            miniMapStyle = prefs.getString(PREF_MINI_MAP_STYLE, "strip") ?: "strip",
            streamNotifications = hudPrefs.getBoolean(PREF_STREAM_NOTIFICATIONS, true),
            showUpcomingSteps = hudPrefs.getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        )
    }

    private fun isImperial(): Boolean = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    private fun formatDist(m: Double): String = if (isImperial()) {
        val feet = m * 3.28084
        val miles = m / 1609.344
        when {
            miles >= 0.1 -> String.format("%.1f mi", miles)
            else -> String.format("%.0f ft", feet)
        }
    } else {
        when {
            m >= 1000 -> String.format("%.1f km", m / 1000)
            else -> String.format("%.0f m", m)
        }
    }

    private fun formatTime(s: Double): String {
        val mins = (s / 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins} min"
    }
}
