package com.yourname.classroomcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class PollingService : Service() {

    // ──────────────────────────────────────────────
    // Static helpers
    // ──────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID_MONITORING   = "classroom_controller"
        const val CHANNEL_ID_ALERTS       = "classroom_alerts"
        const val NOTIF_ID_MONITORING     = 1001
        const val NOTIF_ID_AUTOSTOP_START = 1002
        const val NOTIF_ID_AUTOSTOP_DONE  = 1003
        private const val NOTIF_ID_SUSPICIOUS_BASE = 2000

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID_MONITORING, "교실 관리",
                        NotificationManager.IMPORTANCE_LOW)
                )
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID_ALERTS, "교실 알림",
                        NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }

        @Suppress("MissingPermission")
        fun sendAlertNotification(context: Context, id: Int, title: String, text: String) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    // ──────────────────────────────────────────────
    // Callback interface
    // ──────────────────────────────────────────────

    interface PollCallback {
        fun onPollCycleStarted()
        fun onStatusReceived(address: String, message: String)
        fun onConnectionError(address: String)
    }

    // ──────────────────────────────────────────────
    // Binder
    // ──────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): PollingService = this@PollingService
    }

    private val binder = LocalBinder()

    // ──────────────────────────────────────────────
    // State pushed by MainActivity
    // ──────────────────────────────────────────────

    var addressOrder : List<String>         = emptyList()
    var agentMap     : Map<String, String>  = emptyMap()
    var checkedMap   : Map<String, Boolean> = emptyMap()

    // ──────────────────────────────────────────────
    // Internal state
    // ──────────────────────────────────────────────

    private val handler            = Handler(Looper.getMainLooper())
    private var callback           : PollCallback? = null
    private var pollingStarted     = false
    // address -> last "pkg:HH:mm" key seen, for deduplication
    private val lastSuspiciousKey  = mutableMapOf<String, String>()

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("PollingService", "서비스 실행 중")
        startForeground(NOTIF_ID_MONITORING, buildMonitoringNotification())
        if (!pollingStarted) {
            pollingStarted = true
            handler.postDelayed(pollRunnable, 5000)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        pollingStarted = false
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    fun setCallback(cb: PollCallback?) { callback = cb }

    fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_MONITORING, buildMonitoringNotification())
    }

    // ──────────────────────────────────────────────
    // Polling runnable
    // ──────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            callback?.onPollCycleStarted()
            doPoll()
            handler.postDelayed(this, 5000)
        }
    }

    // ──────────────────────────────────────────────
    // Poll implementation
    // ──────────────────────────────────────────────

    private fun doPoll() {
        android.util.Log.d("Controller", "polling 실행 - 기기 수: ${addressOrder.size}")
        val addresses   = addressOrder.toList()
        val isInSession = getSharedPreferences("classroom", MODE_PRIVATE)
            .getBoolean("isInSession", false)

        addresses.forEach { address ->
            val uri    = URI("ws://$address")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake) { send("STATUS") }

                override fun onMessage(message: String) {
                    android.util.Log.d("PollingService", "STATUS $address: $message")
                    val parts = message.split("|")

                    // Session desync correction
                    if (checkedMap.getOrDefault(address, true)) {
                        val agentInSession = parts.getOrNull(0) == "IN_SESSION"
                        if (isInSession && !agentInSession)  sendCorrection(address, "START")
                        else if (!isInSession && agentInSession) sendCorrection(address, "STOP")
                    }

                    // Suspicious notification (deduplicated per device per event)
                    val suspRaw = parts.find { it.startsWith("SUSPICIOUS:") }
                        ?.removePrefix("SUSPICIOUS:") ?: "none"
                    if (suspRaw != "none") {
                        if (lastSuspiciousKey[address] != suspRaw) {
                            lastSuspiciousKey[address] = suspRaw
                            val deviceName = agentMap[address] ?: address
                            val tokens = suspRaw.split(":")
                            val time   = if (tokens.size >= 3) "${tokens[1]}:${tokens[2]}" else ""
                            val text   = if (time.isNotEmpty())
                                "$deviceName 학생이 차단된 앱 사용을 시도했습니다 ($time)"
                            else
                                "$deviceName 학생이 차단된 앱 사용을 시도했습니다"
                            sendAlertNotification(
                                this@PollingService,
                                NOTIF_ID_SUSPICIOUS_BASE + (address.hashCode() and 0xFFFF),
                                "⚠️ 우회 시도 감지", text
                            )
                        }
                    } else {
                        lastSuspiciousKey.remove(address)
                    }

                    callback?.onStatusReceived(address, message)
                    close()
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {}

                override fun onError(ex: Exception) {
                    android.util.Log.e("PollingService", "연결 끊김 $address: ${ex.message}")
                    callback?.onConnectionError(address)
                }
            }
            client.connect()
        }
    }

    private fun sendCorrection(address: String, command: String) {
        val uri    = URI("ws://$address")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) { send(command) }
            override fun onMessage(message: String) { close() }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {}
        }
        client.connect()
    }

    private fun buildMonitoringNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID_MONITORING)
            .setContentTitle("수업 관리 중")
            .setContentText("${agentMap.size}대 태블릿 모니터링 중")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
