package com.yourname.classroomcontroller

import android.app.AlertDialog
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class MainActivity : AppCompatActivity() {

    // address (IP:port) -> deviceName
    private val agentMap = mutableMapOf<String, String>()
    // displayList와 인덱스를 동기화하는 주소 순서 리스트 (정렬 시 함께 재배열)
    private val addressOrder = mutableListOf<String>()
    private val displayList = mutableListOf<String>()
    // address -> checked (true = 포함, false = 제외)
    private val checkedMap = mutableMapOf<String, Boolean>()
    // 학적 수정 요청 대기열 (순서대로 팝업 표시, 중복 주소 불허)
    private val editRequestQueue = ArrayDeque<String>()
    private var editRequestDialogActive = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollAllAgents()
            handler.postDelayed(this, 5000)
        }
    }

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private lateinit var deviceAdapter: DeviceAdapter

    // ──────────────────────────────────────────────
    // 커스텀 어댑터 (체크박스 포함)
    // ──────────────────────────────────────────────

    inner class DeviceAdapter : BaseAdapter() {
        override fun getCount() = addressOrder.size
        override fun getItem(position: Int): String = displayList[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.list_item_device, parent, false)

            val cbDevice = view.findViewById<CheckBox>(R.id.cbDevice)
            val tvDeviceInfo = view.findViewById<TextView>(R.id.tvDeviceInfo)

            val address = addressOrder[position]
            tvDeviceInfo.text = displayList[position]

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
        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val grade = prefs.getInt("grade", 1)
        val classNum = prefs.getString("class_num", "01") ?: "01"
        return "$grade$classNum"
    }

    private fun showClassroomSetupDialog(cancelable: Boolean = true) {
        val grades = arrayOf("1", "2", "3")
        val classes = Array(20) { i -> String.format("%02d", i + 1) }

        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val currentGrade = prefs.getInt("grade", 1)
        val currentClass = prefs.getString("class_num", "01") ?: "01"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val tvGradeLabel = TextView(this).apply {
            text = "학년"
            textSize = 16f
            setPadding(0, 8, 0, 4)
        }
        val spinnerGrade = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                grades
            )
            setSelection(currentGrade - 1)
        }

        val tvClassLabel = TextView(this).apply {
            text = "반"
            textSize = 16f
            setPadding(0, 16, 0, 4)
        }
        val spinnerClass = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                classes
            )
            setSelection(classes.indexOf(currentClass).coerceAtLeast(0))
        }

        layout.addView(tvGradeLabel)
        layout.addView(spinnerGrade)
        layout.addView(tvClassLabel)
        layout.addView(spinnerClass)

        AlertDialog.Builder(this)
            .setTitle("교실 설정")
            .setView(layout)
            .setCancelable(cancelable)
            .setPositiveButton("확인") { _, _ ->
                val grade = grades[spinnerGrade.selectedItemPosition].toInt()
                val classNum = classes[spinnerClass.selectedItemPosition]
                prefs.edit()
                    .putInt("grade", grade)
                    .putString("class_num", classNum)
                    .putBoolean("filter_set", true)
                    .apply()
                updateClassroomLabel()
                restartDiscovery()
            }
            .show()
    }

    private fun updateClassroomLabel() {
        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val grade = prefs.getInt("grade", 1)
        val classNum = prefs.getString("class_num", "01") ?: "01"
        val classNumInt = classNum.trimStart('0').ifEmpty { "0" }.toInt()
        findViewById<TextView>(R.id.tvClassroomLabel).text = "${grade}학년 ${classNumInt}반"
    }

    private fun restartDiscovery() {
        agentMap.clear()
        addressOrder.clear()
        displayList.clear()
        checkedMap.clear()
        editRequestQueue.clear()
        editRequestDialogActive = false
        deviceAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvDeviceCount).text = "발견된 태블릿: 0대"

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

    // 대기열에 추가하고, 팝업이 없으면 즉시 표시
    private fun enqueueEditRequest(address: String) {
        if (address !in editRequestQueue) {
            editRequestQueue.addLast(address)
        }
        if (!editRequestDialogActive) {
            showNextEditRequestDialog()
        }
    }

    private fun showNextEditRequestDialog() {
        val address = editRequestQueue.firstOrNull() ?: return
        val deviceName = agentMap[address] ?: address
        editRequestDialogActive = true
        showEditRequestDialog(address, deviceName)
    }

    private fun showEditRequestDialog(address: String, deviceName: String) {
        AlertDialog.Builder(this)
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
    }

    // ──────────────────────────────────────────────
    // 정렬 / 상태 파싱
    // ──────────────────────────────────────────────

    private fun sortPriority(displayStr: String): Int = when {
        "❌ 통신 끊김" in displayStr -> 0
        "⚠️접근성" in displayStr -> 1
        "⚠️오버레이" in displayStr -> 2
        "대기중" in displayStr -> 3
        else -> 4
    }

    private fun sortByPriority() {
        val paired = addressOrder.zip(displayList).sortedBy { (_, display) -> sortPriority(display) }
        addressOrder.clear()
        displayList.clear()
        paired.forEach { (addr, display) ->
            addressOrder.add(addr)
            displayList.add(display)
        }
        deviceAdapter.notifyDataSetChanged()
    }

    private fun parseStatusMessage(address: String, message: String): String {
        val deviceName = agentMap[address] ?: address
        val parts = message.split("|")
        val sessionStr = if (parts.getOrNull(0) == "IN_SESSION") "수업중" else "대기중"
        val accessibilityStr = if (parts.find { it.startsWith("ACCESSIBILITY:") }?.endsWith("true") == true) "✅접근성" else "⚠️접근성"
        val overlayStr = if (parts.find { it.startsWith("OVERLAY:") }?.endsWith("true") == true) "✅오버레이" else "⚠️오버레이"
        val editRequest = parts.find { it.startsWith("EDIT_REQUEST:") }?.endsWith("true") == true
        val prefix = if (editRequest) "📝 " else ""
        return "$prefix$deviceName | $sessionStr | $accessibilityStr | $overlayStr"
    }

    // ──────────────────────────────────────────────
    // 폴링
    // ──────────────────────────────────────────────

    private fun pollAllAgents() {
        val addresses = addressOrder.toList()
        addresses.forEach { address ->
            val uri = URI("ws://$address")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake) {
                    send("STATUS")
                }

                override fun onMessage(message: String) {
                    android.util.Log.d("Controller", "상태 확인 $address: $message")

                    val parts = message.split("|")

                    // STATUS 응답에 EDIT_REQUEST:true 포함된 경우 대기열에 추가
                    val hasEditRequest = parts.find { it.startsWith("EDIT_REQUEST:") }?.endsWith("true") == true
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
    }

    // ──────────────────────────────────────────────
    // 생명주기
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val isInSession = prefs.getBoolean("isInSession", false)
        updateSessionUI(isInSession)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        deviceAdapter = DeviceAdapter()
        findViewById<ListView>(R.id.lvDevices).adapter = deviceAdapter

        findViewById<Button>(R.id.btnStart).setOnClickListener { sendToAll("START") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendToAll("STOP") }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showClassroomSetupDialog(cancelable = true)
        }

        handler.postDelayed(pollRunnable, 5000)

        if (!prefs.getBoolean("filter_set", false)) {
            // 최초 실행: 반드시 교실 설정 후 탐색 시작
            showClassroomSetupDialog(cancelable = false)
        } else {
            updateClassroomLabel()
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
                        val ip = info.host.hostAddress ?: return
                        val address = "$ip:${info.port}"
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
                                agentMap[address] = deviceName
                                checkedMap[address] = true  // 신규 기기는 항상 체크됨
                                addressOrder.add(address)
                                displayList.add("$deviceName | 연결 확인중...")
                                sortByPriority()
                            }
                            findViewById<TextView>(R.id.tvDeviceCount).text =
                                "발견된 태블릿: ${agentMap.size}대"
                            android.util.Log.d("Controller", "리스트 크기: ${agentMap.size}")
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                android.util.Log.d("mDNS", "기기 사라짐: ${serviceInfo.serviceName}")
                val deviceName = serviceInfo.serviceName
                val address = agentMap.entries.find { it.value == deviceName }?.key ?: return
                runOnUiThread {
                    val idx = addressOrder.indexOf(address)
                    if (idx >= 0) {
                        agentMap.remove(address)
                        checkedMap.remove(address)        // 재연결 시 체크 초기화
                        editRequestQueue.remove(address)
                        addressOrder.removeAt(idx)
                        displayList.removeAt(idx)
                        deviceAdapter.notifyDataSetChanged()
                        findViewById<TextView>(R.id.tvDeviceCount).text =
                            "발견된 태블릿: ${agentMap.size}대"
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
        if (agentMap.isEmpty()) {
            Toast.makeText(this, "발견된 태블릿이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val isInSession = command == "START"
        getSharedPreferences("classroom", MODE_PRIVATE).edit()
            .putBoolean("isInSession", isInSession)
            .apply()

        updateSessionUI(isInSession)

        // 체크된 기기에만 명령 전송
        addressOrder.filter { checkedMap.getOrDefault(it, true) }.forEach { address ->
            sendCommand(address, command)
        }
    }

    private fun updateSessionUI(isInSession: Boolean) {
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        if (isInSession) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    private fun sendCommand(address: String, command: String) {
        android.util.Log.d("Controller", "연결 시도: $address, 명령: $command")
        val uri = URI("ws://$address")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) {
                send(command)
            }
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

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        super.onDestroy()
    }
}
