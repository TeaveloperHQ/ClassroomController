package com.yourname.classroomcontroller

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SetupWizardActivity : AppCompatActivity() {

    private val DEFAULT_PACKAGES = setOf(
        "com.microsoft.office.onenote",
        "com.yourname.classroomagent",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    // address -> deviceName for agents discovered in this wizard session
    private val discoveredAgents = mutableMapOf<String, String>()
    // packageName -> CheckBox for the app selection list
    private val checkBoxes = mutableMapOf<String, CheckBox>()
    // packageName -> appName, single source of truth for app list
    private val cachedAppMap = mutableMapOf<String, String>()

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var currentStep = 0

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var tvWizardTitle: TextView
    private lateinit var tvStepIndicator: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvDiscoveryCount: TextView
    private lateinit var btnStep3Next: Button

    private val STEP_TITLES = arrayOf(
        "ClassroomController 설정",
        "교실 설정",
        "태블릿 탐색",
        "허용 앱 설정",
        "설정 완료"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_setup_wizard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.wizardRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Load cachedAppMap from SharedPreferences
        val cachedJson = getSharedPreferences("classroom", MODE_PRIVATE)
            .getString("cached_app_map", null)
        if (cachedJson != null) {
            try {
                val json = JSONObject(cachedJson)
                json.keys().forEach { key -> cachedAppMap[key] = json.getString(key) }
            } catch (_: Exception) {}
        }

        viewFlipper      = findViewById(R.id.viewFlipper)
        tvWizardTitle    = findViewById(R.id.tvWizardTitle)
        tvStepIndicator  = findViewById(R.id.tvStepIndicator)
        btnBack          = findViewById(R.id.btnWizardBack)
        tvDiscoveryCount = findViewById(R.id.tvDiscoveryCount)
        btnStep3Next     = findViewById(R.id.btnStep3Next)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        btnBack.setOnClickListener { goBack() }

        // Step 1
        findViewById<Button>(R.id.btnStep1Start).setOnClickListener { goToStep(1) }

        // Step 2
        setupStep2Spinners()
        findViewById<Button>(R.id.btnStep2Next).setOnClickListener { saveClassroomAndProceed() }

        // Step 3
        btnStep3Next.isEnabled = false
        btnStep3Next.alpha     = 0.4f
        findViewById<Button>(R.id.btnStep3Retry).setOnClickListener { restartDiscovery() }
        btnStep3Next.setOnClickListener { goToStep(3) }

        // Step 4
        findViewById<Button>(R.id.btnStep4Analyze).setOnClickListener { startAppAnalysis() }
        findViewById<Button>(R.id.btnStep4Next).setOnClickListener { saveAppsAndProceed() }

        // Step 5
        findViewById<Button>(R.id.btnStep5Finish).setOnClickListener { finishWizard() }

        goToStep(0)
    }

    // ──────────────────────────────────────────────
    // Step navigation
    // ──────────────────────────────────────────────

    private fun goToStep(step: Int) {
        val wasOnDiscovery = (currentStep == 2)
        val nowOnDiscovery = (step == 2)

        if (!wasOnDiscovery && nowOnDiscovery) startDiscovery()
        if (wasOnDiscovery && !nowOnDiscovery) stopDiscovery()

        currentStep = step
        viewFlipper.displayedChild = step
        tvWizardTitle.text    = STEP_TITLES[step]
        tvStepIndicator.text  = "${step + 1}/5"
        btnBack.visibility    = if (step > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun goBack() {
        if (currentStep > 0) goToStep(currentStep - 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentStep > 0) goBack() else super.onBackPressed()
    }

    // ──────────────────────────────────────────────
    // Step 2: Classroom setup
    // ──────────────────────────────────────────────

    private fun setupStep2Spinners() {
        val prefs        = getSharedPreferences("classroom", MODE_PRIVATE)
        val currentGrade = prefs.getInt("grade", 1)
        val currentClass = prefs.getString("class_num", "01") ?: "01"
        val grades       = arrayOf("1", "2", "3")
        val classes      = Array(20) { i -> String.format("%02d", i + 1) }

        val spinnerGrade = findViewById<Spinner>(R.id.spinnerWizardGrade)
        val spinnerClass = findViewById<Spinner>(R.id.spinnerWizardClass)

        spinnerGrade.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, grades)
        spinnerGrade.setSelection(currentGrade - 1)

        spinnerClass.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, classes)
        spinnerClass.setSelection(classes.indexOf(currentClass).coerceAtLeast(0))
    }

    private fun saveClassroomAndProceed() {
        val grades      = arrayOf("1", "2", "3")
        val classes     = Array(20) { i -> String.format("%02d", i + 1) }
        val spinnerGrade = findViewById<Spinner>(R.id.spinnerWizardGrade)
        val spinnerClass = findViewById<Spinner>(R.id.spinnerWizardClass)

        val grade    = grades[spinnerGrade.selectedItemPosition].toInt()
        val classNum = classes[spinnerClass.selectedItemPosition]

        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putInt("grade", grade)
            .putString("class_num", classNum)
            .putBoolean("filter_set", true)
            .apply()

        goToStep(2)
    }

    // ──────────────────────────────────────────────
    // Step 3: mDNS discovery
    // ──────────────────────────────────────────────

    private fun startDiscovery() {
        discoveredAgents.clear()
        updateDiscoveryCount()
        btnStep3Next.isEnabled = false
        btnStep3Next.alpha     = 0.4f

        val prefs        = getSharedPreferences("classroom", MODE_PRIVATE)
        val grade        = prefs.getInt("grade", 1)
        val classNum     = prefs.getString("class_num", "01") ?: "01"
        val filterPrefix = "$grade$classNum"

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)  {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip         = info.host.hostAddress ?: return
                        val address    = "$ip:${info.port}"
                        val deviceName = info.serviceName
                        if (!deviceName.startsWith(filterPrefix)) return
                        runOnUiThread {
                            if (!discoveredAgents.containsKey(address)) {
                                discoveredAgents[address] = deviceName
                                updateDiscoveryCount()
                                if (discoveredAgents.isNotEmpty()) {
                                    btnStep3Next.isEnabled = true
                                    btnStep3Next.alpha     = 1.0f
                                }
                            }
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val address = discoveredAgents.entries
                    .find { it.value == serviceInfo.serviceName }?.key ?: return
                runOnUiThread {
                    discoveredAgents.remove(address)
                    updateDiscoveryCount()
                    if (discoveredAgents.isEmpty()) {
                        btnStep3Next.isEnabled = false
                        btnStep3Next.alpha     = 0.4f
                    }
                }
            }
        }

        nsdManager.discoverServices(
            "_classroomagent._tcp.",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener!!
        )
    }

    private fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
            discoveryListener = null
        }
    }

    private fun restartDiscovery() {
        stopDiscovery()
        handler.postDelayed({ startDiscovery() }, 300)
    }

    private fun updateDiscoveryCount() {
        tvDiscoveryCount.text = "${discoveredAgents.size}대 발견됨"
    }

    // ──────────────────────────────────────────────
    // Step 4: App analysis
    // ──────────────────────────────────────────────

    private fun startAppAnalysis() {
        val btnAnalyze = findViewById<Button>(R.id.btnStep4Analyze)
        btnAnalyze.isEnabled = false
        btnAnalyze.text      = "분석 중..."

        val selectedAgents = discoveredAgents.keys.toList().shuffled().take(3)
        val responses      = mutableMapOf<String, Map<String, String>>()
        val pendingCount   = AtomicInteger(selectedAgents.size)
        val done           = AtomicBoolean(false)

        fun finalize() {
            if (!done.compareAndSet(false, true)) return
            val intersection: Map<String, String> = if (responses.isEmpty()) {
                emptyMap()
            } else {
                val commonPkgs = responses.values
                    .map { it.keys.toSet() }
                    .reduce { acc, set -> acc intersect set }
                commonPkgs.associateWith { pkg ->
                    responses.values.firstNotNullOfOrNull { it[pkg] } ?: pkg
                }
            }
            // Merge into cachedAppMap (never shrink) and persist
            cachedAppMap.putAll(intersection)
            val json = JSONObject()
            cachedAppMap.forEach { (k, v) -> json.put(k, v) }
            getSharedPreferences("classroom", MODE_PRIVATE).edit()
                .putString("cached_app_map", json.toString()).apply()
            runOnUiThread { buildAppChecklist(cachedAppMap) }
        }

        handler.postDelayed({ finalize() }, 5000)

        selectedAgents.forEach { address ->
            val uri    = URI("ws://$address")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake) { send("GET_APPS") }

                override fun onMessage(message: String) {
                    if (message.startsWith("APP_LIST")) {
                        val appMap = message.split("|").drop(1).mapNotNull { entry ->
                            val idx = entry.indexOf(':')
                            if (idx > 0) entry.substring(idx + 1) to entry.substring(0, idx)
                            else null
                        }.toMap()
                        synchronized(responses) { responses[address] = appMap }
                    }
                    if (pendingCount.decrementAndGet() == 0) finalize()
                    close()
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {}

                override fun onError(ex: Exception) {
                    if (pendingCount.decrementAndGet() == 0) finalize()
                }
            }
            client.connect()
        }
    }

    private fun buildAppChecklist(appMap: Map<String, String>) {
        val container = findViewById<LinearLayout>(R.id.containerApps)
        container.removeAllViews()
        checkBoxes.clear()

        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val savedAllowedApps = getSharedPreferences("classroom", MODE_PRIVATE)
            .getStringSet("allowed_apps", emptySet()) ?: emptySet()

        // Default packages – pre-checked, all checkboxes enabled (user can uncheck)
        DEFAULT_PACKAGES.forEach { pkg ->
            val appName = appMap[pkg] ?: pkg
            val cb = CheckBox(this).apply {
                text      = "$appName (기본)"
                isChecked = true
                isEnabled = true
                setPadding(dp4, dp4, dp4, dp4)
            }
            checkBoxes[pkg] = cb
            container.addView(cb)
        }

        // Non-default apps – checked if previously saved in allowed_apps
        appMap.entries
            .filter { it.key !in DEFAULT_PACKAGES }
            .sortedBy { it.value }
            .forEach { (pkg, name) ->
                val cb = CheckBox(this).apply {
                    text      = name
                    isChecked = pkg in savedAllowedApps
                    setPadding(dp4, dp4, dp4, dp4)
                }
                checkBoxes[pkg] = cb
                container.addView(cb)
            }

        // Switch UI: hide analyze button + loading text, show list + next
        findViewById<View>(R.id.tvStep4LoadingDesc).visibility = View.GONE
        findViewById<View>(R.id.btnStep4Analyze).visibility    = View.GONE
        findViewById<View>(R.id.scrollStep4).visibility        = View.VISIBLE
        findViewById<View>(R.id.btnStep4Next).visibility       = View.VISIBLE
    }

    private fun saveAppsAndProceed() {
        val selected = checkBoxes.entries
            .filter { it.value.isChecked }
            .map { it.key }
            .toSet()

        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putStringSet("allowed_apps", selected)
            .commit() // commit() instead of apply() to ensure write completes before proceeding

        Log.d("Wizard", "저장된 앱 수: ${selected.size}")

        goToStep(4)
    }

    // ──────────────────────────────────────────────
    // Step 5: Complete
    // ──────────────────────────────────────────────

    private fun finishWizard() {
        val savedApps = getSharedPreferences("classroom", MODE_PRIVATE)
            .getStringSet("allowed_apps", emptySet()) ?: emptySet()

        if (savedApps.isNotEmpty()) {
            val command = "SET_ALLOWED_APPS|" + savedApps.joinToString("|")
            discoveredAgents.keys.forEach { address -> sendCommand(address, command) }
        }

        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putBoolean("wizard_done", true)
            .apply()

        setResult(RESULT_OK)
        finish()
    }

    // ──────────────────────────────────────────────
    // WebSocket helper
    // ──────────────────────────────────────────────

    private fun sendCommand(address: String, command: String) {
        val uri    = URI("ws://$address")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) { send(command) }
            override fun onMessage(message: String) { close() }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {}
        }
        client.connect()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
