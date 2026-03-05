package com.yourname.classroomcontroller

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class MainActivity : AppCompatActivity() {

    // address (IP:port) -> deviceName (조회 전용, 순서 관리 안 함)
    private val agentMap = mutableMapOf<String, String>()
    // displayList와 인덱스를 동기화하는 주소 순서 리스트 (정렬 시 함께 재배열)
    private val addressOrder = mutableListOf<String>()
    private val displayList = mutableListOf<String>()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollAllAgents()
            handler.postDelayed(this, 5000)
        }
    }

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private fun sortPriority(displayStr: String): Int = when {
        "❌ 통신 끊김" in displayStr -> 0
        "⚠️접근성" in displayStr -> 1
        "⚠️오버레이" in displayStr -> 2
        "대기중" in displayStr -> 3
        else -> 4
    }

    // addressOrder와 displayList를 쌍으로 묶어 정렬 후 제자리 재배열
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
        return "$deviceName | $sessionStr | $accessibilityStr | $overlayStr"
    }

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

                    // 상태 불일치 강제 보정
                    val controllerInSession = getSharedPreferences("classroom", MODE_PRIVATE)
                        .getBoolean("isInSession", false)
                    val agentInSession = message.split("|").getOrNull(0) == "IN_SESSION"
                    if (controllerInSession && !agentInSession) {
                        sendCommand(address, "START")
                    } else if (!controllerInSession && agentInSession) {
                        sendCommand(address, "STOP")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("classroom", MODE_PRIVATE)
        val isInSession = prefs.getBoolean("isInSession", false)
        updateSessionUI(isInSession)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        findViewById<ListView>(R.id.lvDevices).adapter = deviceAdapter

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            sendToAll("START")
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendToAll("STOP")
        }

        startDiscovery()
        handler.postDelayed(pollRunnable, 5000)
    }

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
                        android.util.Log.d("mDNS", "주소 확인됨: $address ($deviceName)")
                        runOnUiThread {
                            if (!agentMap.containsKey(address)) {
                                agentMap[address] = deviceName
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
                // 사라진 기기 제거는 실제 기기 테스트 후 구현
            }
        }

        nsdManager.discoverServices(
            "_classroomagent._tcp.",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener!!
        )
    }

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

        addressOrder.forEach { address ->
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
