package com.yourname.classroomcontroller

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

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
    private var allowedApps  = mutableSetOf<String>()
    private val cachedAppMap = mutableMapOf<String, String>() // packageName -> appName

    // address (IP:port) -> deviceName
    private val agentMap     = mutableMapOf<String, String>()
    private val addressOrder = mutableListOf<String>()
    private val displayList  = mutableListOf<String>()
    // address -> checked (true = 포함, false = 제외)
    private val checkedMap   = mutableMapOf<String, Boolean>()
    // 학적 수정 요청 대기열 (순서대로 팝업 표시, 중복 주소 불허)
    private val editRequestQueue = ArrayDeque<String>()
    private var editRequestDialogActive = false

    private lateinit var notificationManager: NotificationManager
    private val notifiedSuspicious = mutableSetOf<String>()
    private val isInSession: Boolean
        get() = getSharedPreferences("classroom", MODE_PRIVATE).getBoolean("isInSession", false)

    private val wizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val savedApps = prefs.getStringSet("allowed_apps", null)
        if (savedApps != null) allowedApps = savedApps.toMutableSet()
        val cachedJson = prefs.getString("cached_app_map", null)
        if (cachedJson != null) {
            try {
                val json = JSONObject(cachedJson)
                json.keys().forEach { key -> cachedAppMap[key] = json.getString(key) }
            } catch (_: Exception) {}
        }
        restartDiscovery()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollAllAgents()
            handler.postDelayed(this, 5000)
        }
    }

    // ── 자동 종료 ──
    private val COUNTDOWN_DURATION_MS = 5L * 60 * 1000   // 5분
    private var peakAgentCount  = 0
    private var autoStopPhase   = false
    private var countdownStartMs = 0L
    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            val elapsed   = SystemClock.elapsedRealtime() - countdownStartMs
            val remaining = COUNTDOWN_DURATION_MS - elapsed
            if (remaining <= 0) { executeAutoStop(); return }
            val secondsElapsed = (elapsed / 1000L).toInt().coerceAtMost(300)
            findViewById<ProgressBar>(R.id.pbAutoStop).progress = secondsElapsed
            findViewById<TextView>(R.id.tvAutoStop).text =
                "자동 종료 대기중 · 현재 ${agentMap.size}대"
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private lateinit var deviceAdapter: DeviceAdapter

    // ──────────────────────────────────────────────
    // 상태 색상
    // ──────────────────────────────────────────────

    private fun statusColorFor(displayStr: String): Int = when {
        "🚨 우회" in displayStr      -> Color.parseColor("#9c27b0")
        "❌ 통신 끊김" in displayStr -> Color.parseColor("#d32f2f")
        "⚠️" in displayStr          -> Color.parseColor("#e65100")
        "대기중" in displayStr       -> Color.parseColor("#9e9e9e")
        "연결 확인중" in displayStr  -> Color.parseColor("#9e9e9e")
        else                         -> Color.parseColor("#2e7d32")
    }

    // ──────────────────────────────────────────────
    // 커스텀 어댑터 (CardView + 체크박스 + 상태 색상)
    // ──────────────────────────────────────────────

    inner class DeviceAdapter : BaseAdapter() {
        override fun getCount() = addressOrder.size
        override fun getItem(position: Int): String = displayList[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.list_item_device, parent, false)

            val cbDevice       = view.findViewById<CheckBox>(R.id.cbDevice)
            val tvDeviceName   = view.findViewById<TextView>(R.id.tvDeviceName)
            val tvDeviceStatus = view.findViewById<TextView>(R.id.tvDeviceStatus)
            val viewBorder     = view.findViewById<View>(R.id.viewStatusBorder)
            val viewDot        = view.findViewById<View>(R.id.viewStatusDot)

            val address = addressOrder[position]
            val full    = displayList[position]

            // displayList 형식: "[📝 ]기기명 | 상태 | ..." 또는 "기기명 | ❌ 통신 끊김"
            val sepIdx     = full.indexOf(" | ")
            val namePart   = if (sepIdx >= 0) full.substring(0, sepIdx) else full
            val statusPart = if (sepIdx >= 0) full.substring(sepIdx + 3) else ""

            tvDeviceName.text   = namePart
            tvDeviceStatus.text = statusPart

            val color = statusColorFor(full)
            viewBorder.setBackgroundColor(color)
            tvDeviceStatus.setTextColor(color)
            viewDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }

            // 리스너 먼저 제거 후 상태 설정 (recycled view 오작동 방지)
            cbDevice.setOnCheckedChangeListener(null)
            cbDevice.isChecked = checkedMap.getOrDefault(address, true)
            cbDevice.setOnCheckedChangeListener { _, isChecked ->
                checkedMap[address] = isChecked
            }

            return view
        }
    }

    // ──────────────────────────────────────────────
    // 교실 필터
    // ──────────────────────────────────────────────

    private fun getFilterPrefix(): String {
        val prefs    = getSharedPreferences("classroom", MODE_PRIVATE)
        val grade    = prefs.getInt("grade", 1)
        val classNum = prefs.getString("class_num", "01") ?: "01"
        return "$grade$classNum"
    }

    private fun showSettingsMenu() {
        val options = arrayOf("🏫 학급 설정", "🧙 초기 설정 마법사 재실행")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClassroomSetupDialog(cancelable = true)
                    1 -> wizardLauncher.launch(Intent(this, SetupWizardActivity::class.java))
                }
            }
            .show()
    }

    private fun showClassroomSetupDialog(cancelable: Boolean = true) {
        val grades  = arrayOf("1", "2", "3")
        val classes = Array(20) { i -> String.format("%02d", i + 1) }

        val prefs        = getSharedPreferences("classroom", MODE_PRIVATE)
        val currentGrade = prefs.getInt("grade", 1)
        val currentClass = prefs.getString("class_num", "01") ?: "01"

        val sheet     = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_classroom_setup, null)

        val spinnerGrade = sheetView.findViewById<Spinner>(R.id.spinnerGrade)
        val spinnerClass = sheetView.findViewById<Spinner>(R.id.spinnerClass)
        val btnConfirm   = sheetView.findViewById<Button>(R.id.btnConfirm)

        spinnerGrade.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, grades)
        spinnerGrade.setSelection(currentGrade - 1)

        spinnerClass.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, classes)
        spinnerClass.setSelection(classes.indexOf(currentClass).coerceAtLeast(0))

        btnConfirm.setOnClickListener {
            val grade    = grades[spinnerGrade.selectedItemPosition].toInt()
            val classNum = classes[spinnerClass.selectedItemPosition]
            prefs.edit()
                .putInt("grade", grade)
                .putString("class_num", classNum)
                .putBoolean("filter_set", true)
                .apply()
            updateAppBarSubtitle()
            restartDiscovery()
            sheet.dismiss()
        }

        sheet.setCancelable(cancelable)
        sheet.setContentView(sheetView)
        sheet.show()
    }

    private fun updateAppBarSubtitle() {
        val prefs       = getSharedPreferences("classroom", MODE_PRIVATE)
        val grade       = prefs.getInt("grade", 1)
        val classNum    = prefs.getString("class_num", "01") ?: "01"
        val classNumInt = classNum.trimStart('0').ifEmpty { "0" }.toInt()
        val count       = agentMap.size
        findViewById<TextView>(R.id.tvAppBarSubtitle).text =
            "${grade}학년 ${classNumInt}반 · 태블릿 ${count}대"
    }

    private fun restartDiscovery() {
        agentMap.clear()
        addressOrder.clear()
        displayList.clear()
        checkedMap.clear()
        editRequestQueue.clear()
        editRequestDialogActive = false
        deviceAdapter.notifyDataSetChanged()
        updateAppBarSubtitle()

        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener!!)
            } catch (e: Exception) {
                android.util.Log.e("mDNS", "탐색 중지 실패: ${e.message}")
            }
            discoveryListener = null
        }
        handler.postDelayed({ startDiscovery() }, 500)
    }

    // ──────────────────────────────────────────────
    // 학적 수정 요청
    // ──────────────────────────────────────────────

    private fun enqueueEditRequest(address: String) {
        if (address !in editRequestQueue) {
            editRequestQueue.addLast(address)
        }
        if (!editRequestDialogActive) {
            showNextEditRequestDialog()
        }
    }

    private fun showNextEditRequestDialog() {
        val address    = editRequestQueue.firstOrNull() ?: return
        val deviceName = agentMap[address] ?: address
        editRequestDialogActive = true
        showEditRequestDialog(address, deviceName)
    }

    private fun showEditRequestDialog(address: String, deviceName: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("학적 수정 요청")
            .setMessage("$deviceName 학적 수정 요청")
            .setCancelable(false)
            .setPositiveButton("허락") { _, _ ->
                editRequestQueue.remove(address)
                editRequestDialogActive = false
                sendCommand(address, "EDIT_APPROVED")
                showNextEditRequestDialog()
            }
            .setNegativeButton("거절") { _, _ ->
                editRequestQueue.remove(address)
                editRequestDialogActive = false
                sendCommand(address, "EDIT_REJECTED")
                showNextEditRequestDialog()
            }
            .show()

        // 버튼 스타일: 허락 = 파란 배경 흰 텍스트, 거절 = 빨간 텍스트만
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setBackgroundColor(Color.parseColor("#1976d2"))
            setTextColor(Color.WHITE)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#d32f2f"))
        }
    }

    // ──────────────────────────────────────────────
    // 정렬 / 상태 파싱
    // ──────────────────────────────────────────────

    private fun sortPriority(displayStr: String): Int = when {
        "🚨 우회" in displayStr      -> 0
        "❌ 통신 끊김" in displayStr -> 1
        "⚠️접근성" in displayStr    -> 2
        "⚠️오버레이" in displayStr  -> 3
        "대기중" in displayStr      -> 4
        else                        -> 5
    }

    private fun sortByPriority() {
        val paired = addressOrder.zip(displayList)
            .sortedWith(
                compareBy<Pair<String, String>> { (_, display) -> sortPriority(display) }
                    .thenBy { (_, display) ->
                        // 그룹 5(정상 수업중) 내에서만 기기명 오름차순 (학번순)
                        if (sortPriority(display) == 5) {
                            val sep = display.indexOf(" | ")
                            val name = if (sep >= 0) display.substring(0, sep) else display
                            name.removePrefix("📝 ")
                        } else ""
                    }
            )
        addressOrder.clear()
        displayList.clear()
        paired.forEach { (addr, display) ->
            addressOrder.add(addr)
            displayList.add(display)
        }
        deviceAdapter.notifyDataSetChanged()
    }

    private fun parseStatusMessage(address: String, message: String): String {
        val deviceName  = agentMap[address] ?: address
        val parts       = message.split("|")
        val editRequest = parts.find { it.startsWith("EDIT_REQUEST:") }
                ?.endsWith("true") == true
        val prefix      = if (editRequest) "📝 " else ""

        // SUSPICIOUS 처리: "none"이 아니면 🚨 표시로 대체
        val suspRaw = parts.find { it.startsWith("SUSPICIOUS:") }
                ?.removePrefix("SUSPICIOUS:") ?: "none"
        if (suspRaw != "none") {
            val tokens  = suspRaw.split(":")
            val pkg     = tokens.getOrNull(0) ?: suspRaw
            val time    = if (tokens.size >= 3) "${tokens[1]}:${tokens[2]}" else ""
            val appName = cachedAppMap[pkg] ?: pkg
            val timeStr = if (time.isNotEmpty()) " ($time)" else ""
            if (deviceName !in notifiedSuspicious) {
                sendAlertNotification(2001, "⚠️ 우회 시도 감지",
                    "${deviceName} 학생이 차단된 앱 사용을 시도했습니다 (${time})")
                notifiedSuspicious.add(deviceName)
            }
            return "$prefix$deviceName | 🚨 우회 시도 감지: $appName$timeStr"
        }
        notifiedSuspicious.remove(deviceName)

        val sessionStr       = if (parts.getOrNull(0) == "IN_SESSION") "수업중" else "대기중"
        val accessibilityStr = if (parts.find { it.startsWith("ACCESSIBILITY:") }
                ?.endsWith("true") == true) "✅접근성" else "⚠️접근성"
        val overlayStr       = if (parts.find { it.startsWith("OVERLAY:") }
                ?.endsWith("true") == true) "✅오버레이" else "⚠️오버레이"
        return "$prefix$deviceName | $sessionStr | $accessibilityStr | $overlayStr"
    }

    // ──────────────────────────────────────────────
    // 폴링
    // ──────────────────────────────────────────────

    private fun pollAllAgents() {
        checkPeakAndAutoStop()
        val addresses = addressOrder.toList()
        addresses.forEach { address ->
            val uri    = URI("ws://$address")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake) {
                    send("STATUS")
                }

                override fun onMessage(message: String) {
                    android.util.Log.d("Controller", "상태 확인 $address: $message")

                    val parts = message.split("|")

                    // STATUS 응답에 EDIT_REQUEST:true 포함 시 대기열에 추가
                    val hasEditRequest = parts.find { it.startsWith("EDIT_REQUEST:") }
                        ?.endsWith("true") == true
                    if (hasEditRequest) {
                        runOnUiThread { enqueueEditRequest(address) }
                    }

                    // 체크된 기기만 상태 불일치 강제 보정
                    if (checkedMap.getOrDefault(address, true)) {
                        val controllerInSession = getSharedPreferences("classroom", MODE_PRIVATE)
                            .getBoolean("isInSession", false)
                        val agentInSession = parts.getOrNull(0) == "IN_SESSION"
                        if (controllerInSession && !agentInSession) {
                            sendCommand(address, "START")
                        } else if (!controllerInSession && agentInSession) {
                            sendCommand(address, "STOP")
                        }
                    }

                    val formatted = parseStatusMessage(address, message)
                    this@MainActivity.runOnUiThread {
                        val idx = addressOrder.indexOf(address)
                        if (idx >= 0) {
                            displayList[idx] = formatted
                            sortByPriority()
                        }
                    }
                    close()
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {}

                override fun onError(ex: Exception) {
                    android.util.Log.e("Controller", "연결 끊김 $address: ${ex.message}")
                    val deviceName = agentMap[address] ?: address
                    this@MainActivity.runOnUiThread {
                        val idx = addressOrder.indexOf(address)
                        if (idx >= 0) {
                            displayList[idx] = "$deviceName | ❌ 통신 끊김"
                            sortByPriority()
                        }
                    }
                }
            }
            client.connect()
        }
        if (isInSession) notificationManager.notify(1001, buildSessionNotification(agentMap.size))
    }

    // ──────────────────────────────────────────────
    // 생명주기
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelController = NotificationChannel("classroom_controller", "교실 관리", NotificationManager.IMPORTANCE_LOW)
            channelController.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            notificationManager.createNotificationChannel(channelController)

            val channelAlerts = NotificationChannel("classroom_alerts", "교실 알림", NotificationManager.IMPORTANCE_HIGH)
            channelAlerts.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            notificationManager.createNotificationChannel(channelAlerts)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        val prefs       = getSharedPreferences("classroom", MODE_PRIVATE)
        val isInSession = prefs.getBoolean("isInSession", false)
        updateSessionUI(isInSession)

        // Restore allowed apps
        val savedApps = prefs.getStringSet("allowed_apps", null)
        if (savedApps != null) {
            allowedApps = savedApps.toMutableSet()
        }

        // Restore cached app name map (packageName -> appName)
        val cachedJson = prefs.getString("cached_app_map", null)
        if (cachedJson != null) {
            try {
                val json = JSONObject(cachedJson)
                json.keys().forEach { key -> cachedAppMap[key] = json.getString(key) }
            } catch (_: Exception) {}
        }

        nsdManager    = getSystemService(NSD_SERVICE) as NsdManager
        deviceAdapter = DeviceAdapter()
        findViewById<ListView>(R.id.lvDevices).adapter = deviceAdapter

        findViewById<Button>(R.id.btnStart).setOnClickListener { sendToAll("START") }
        findViewById<Button>(R.id.btnStop).setOnClickListener  { sendToAll("STOP") }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showSettingsMenu()
        }
        findViewById<ImageButton>(R.id.btnAppSettings).setOnClickListener {
            getAppsAndShowDialog()
        }

        handler.postDelayed(pollRunnable, 5000)

        if (!prefs.getBoolean("wizard_done", false)) {
            wizardLauncher.launch(Intent(this, SetupWizardActivity::class.java))
        } else if (!prefs.getBoolean("filter_set", false)) {
            showClassroomSetupDialog(cancelable = false)
        } else {
            updateAppBarSubtitle()
            startDiscovery()
        }
    }

    // ──────────────────────────────────────────────
    // NSD 탐색
    // ──────────────────────────────────────────────

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.e("mDNS", "탐색 시작 실패: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {
                android.util.Log.d("mDNS", "탐색 시작됨")
            }
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                android.util.Log.d("mDNS", "기기 발견: ${serviceInfo.serviceName}")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        android.util.Log.e("mDNS", "주소 확인 실패: $errorCode")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip         = info.host.hostAddress ?: return
                        val address    = "$ip:${info.port}"
                        val deviceName = info.serviceName

                        // 교실 필터: 기기 이름이 {학년}{반} 으로 시작하는 것만 허용
                        val prefix = getFilterPrefix()
                        if (!deviceName.startsWith(prefix)) {
                            android.util.Log.d("mDNS", "필터 제외: $deviceName (prefix: $prefix)")
                            return
                        }

                        android.util.Log.d("mDNS", "주소 확인됨: $address ($deviceName)")
                        runOnUiThread {
                            if (!agentMap.containsKey(address)) {
                                agentMap[address]   = deviceName
                                checkedMap[address] = true  // 신규 기기는 항상 체크됨
                                addressOrder.add(address)
                                displayList.add("$deviceName | 연결 확인중...")
                                sortByPriority()
                                if (allowedApps.isNotEmpty()) {
                                    val cmd = "SET_ALLOWED_APPS|" + allowedApps.joinToString("|")
                                    sendCommand(address, cmd)
                                }
                                checkPeakAndAutoStop()
                            }
                            updateAppBarSubtitle()
                            android.util.Log.d("Controller", "리스트 크기: ${agentMap.size}")
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                android.util.Log.d("mDNS", "기기 사라짐: ${serviceInfo.serviceName}")
                val deviceName = serviceInfo.serviceName
                val address    = agentMap.entries.find { it.value == deviceName }?.key ?: return
                runOnUiThread {
                    val idx = addressOrder.indexOf(address)
                    if (idx >= 0) {
                        agentMap.remove(address)
                        checkedMap.remove(address)       // 재연결 시 체크 초기화
                        editRequestQueue.remove(address)
                        addressOrder.removeAt(idx)
                        displayList.removeAt(idx)
                        deviceAdapter.notifyDataSetChanged()
                        updateAppBarSubtitle()
                        checkPeakAndAutoStop()
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

    // ──────────────────────────────────────────────
    // 명령 전송
    // ──────────────────────────────────────────────

    private fun sendToAll(command: String) {
        android.util.Log.d("Controller", "발견된 기기 수: ${agentMap.size}, 목록: ${agentMap.keys}")

        // 자동 종료 상태 초기화 (START/STOP 모두)
        handler.removeCallbacks(countdownTickRunnable)
        autoStopPhase  = false
        peakAgentCount = 0
        findViewById<LinearLayout>(R.id.llAutoStop).visibility = View.GONE

        if (agentMap.isEmpty() && command == "START") {
            Toast.makeText(this, "발견된 태블릿이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val isInSession = command == "START"
        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putBoolean("isInSession", isInSession)
            .apply()

        updateSessionUI(isInSession)

        if (isInSession) {
            notificationManager.notify(1001, buildSessionNotification(agentMap.size))
        } else {
            notificationManager.cancel(1001)
        }

        // 체크된 기기에만 명령 전송
        addressOrder.filter { checkedMap.getOrDefault(it, true) }.forEach { address ->
            sendCommand(address, command)
        }
    }

    private fun updateSessionUI(isInSession: Boolean) {
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop  = findViewById<Button>(R.id.btnStop)
        if (isInSession) {
            btnStart.isEnabled = false
            btnStart.alpha     = 0.4f
            btnStop.isEnabled  = true
            btnStop.alpha      = 1.0f
        } else {
            btnStart.isEnabled = true
            btnStart.alpha     = 1.0f
            btnStop.isEnabled  = false
            btnStop.alpha      = 0.4f
        }
    }

    private fun sendCommand(address: String, command: String) {
        android.util.Log.d("Controller", "연결 시도: $address, 명령: $command")
        val uri    = URI("ws://$address")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) { send(command) }
            override fun onMessage(message: String) {
                android.util.Log.d("Controller", "응답: $message")
                close()
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {
                android.util.Log.e("Controller", "연결 실패: ${ex.message}")
            }
        }
        client.connect()
    }

    // ──────────────────────────────────────────────
    // 자동 종료
    // ──────────────────────────────────────────────

    private fun checkPeakAndAutoStop() {
        val isInSession = getSharedPreferences("classroom", MODE_PRIVATE)
            .getBoolean("isInSession", false)
        if (!isInSession) return

        val current = agentMap.size
        if (current >= peakAgentCount) {
            if (current > peakAgentCount) peakAgentCount = current
            if (autoStopPhase) cancelAutoStopCountdown()
        } else if (!autoStopPhase) {
            if (peakAgentCount > 0 && current <= peakAgentCount / 3) {
                startAutoStopCountdown()
            }
        }
    }

    private fun startAutoStopCountdown() {
        autoStopPhase    = true
        countdownStartMs = SystemClock.elapsedRealtime()
        findViewById<LinearLayout>(R.id.llAutoStop).visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.pbAutoStop).progress   = 0
        findViewById<TextView>(R.id.tvAutoStop).text =
            "자동 종료 대기중 · 현재 ${agentMap.size}대"
        handler.post(countdownTickRunnable)
        sendAlertNotification(2002, "⏳ 자동 수업 종료 예정",
            "태블릿 1/3 이상 오프라인. 5분 후 수업이 자동 종료됩니다")
    }

    private fun cancelAutoStopCountdown() {
        autoStopPhase = false
        handler.removeCallbacks(countdownTickRunnable)
        findViewById<LinearLayout>(R.id.llAutoStop).visibility = View.GONE
    }

    private fun executeAutoStop() {
        autoStopPhase  = false
        peakAgentCount = 0
        handler.removeCallbacks(countdownTickRunnable)
        findViewById<LinearLayout>(R.id.llAutoStop).visibility = View.GONE

        addressOrder.filter { checkedMap.getOrDefault(it, true) }.forEach { address ->
            sendCommand(address, "STOP")
        }
        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putBoolean("isInSession", false).apply()
        updateSessionUI(false)
        Toast.makeText(this, "수업이 자동 종료되었습니다", Toast.LENGTH_LONG).show()
        sendAlertNotification(2002, "🔓 수업 종료",
            "수업이 자동 종료되었습니다")
    }

    // ──────────────────────────────────────────────
    // 허용 앱 설정
    // ──────────────────────────────────────────────

    private fun getAppsAndShowDialog() {
        if (agentMap.isEmpty()) {
            if (cachedAppMap.isNotEmpty()) {
                Toast.makeText(this, "이전에 저장된 앱 목록을 사용합니다", Toast.LENGTH_SHORT).show()
                showAppSelectionDialog(cachedAppMap)
            } else {
                Toast.makeText(this, "발견된 태블릿이 없고 저장된 앱 목록도 없습니다", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val loadingDialog = AlertDialog.Builder(this)
            .setMessage("앱 목록 수집 중...")
            .setCancelable(false)
            .show()

        val selectedAgents = addressOrder.shuffled().take(3)
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
            runOnUiThread {
                loadingDialog.dismiss()
                showAppSelectionDialog(cachedAppMap)
            }
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
                    android.util.Log.e("Controller", "GET_APPS 실패 $address: ${ex.message}")
                    if (pendingCount.decrementAndGet() == 0) finalize()
                }
            }
            client.connect()
        }
    }

    private fun showAppSelectionDialog(appMap: Map<String, String>) {
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val dp8 = (8 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp8, dp8, dp8, dp8)
        }

        val checkBoxes = mutableMapOf<String, CheckBox>()

        // Default packages – pre-checked, all enabled (user can uncheck if needed)
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

        // Non-default packages – checked only if previously in allowed_apps
        appMap.entries
            .filter { it.key !in DEFAULT_PACKAGES }
            .sortedBy { it.value }
            .forEach { (pkg, name) ->
                val cb = CheckBox(this).apply {
                    text      = name
                    isChecked = pkg in allowedApps
                    isEnabled = true
                    setPadding(dp4, dp4, dp4, dp4)
                }
                checkBoxes[pkg] = cb
                container.addView(cb)
            }

        val scrollView = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("허용 앱 설정")
            .setView(scrollView)
            .setPositiveButton("확인") { _, _ ->
                val selected = checkBoxes.entries
                    .filter { it.value.isChecked }
                    .map { it.key }
                    .toMutableSet()
                allowedApps = selected
                getSharedPreferences("classroom", MODE_PRIVATE).edit()
                    .putStringSet("allowed_apps", selected)
                    .apply()
                val command = "SET_ALLOWED_APPS|" + selected.joinToString("|")
                addressOrder.forEach { address -> sendCommand(address, command) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ──────────────────────────────────────────────
    // 알림 헬퍼
    // ──────────────────────────────────────────────

    private fun buildSessionNotification(count: Int): Notification =
        NotificationCompat.Builder(this, "classroom_controller")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("수업 관리 중")
            .setContentText("${count}대 태블릿 모니터링 중")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun sendAlertNotification(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, "classroom_alerts")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager.notify(id, notification)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(countdownTickRunnable)
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        super.onDestroy()
    }
}
