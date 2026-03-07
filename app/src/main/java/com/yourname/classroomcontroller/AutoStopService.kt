package com.yourname.classroomcontroller

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class AutoStopService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    var onCountdownComplete: (() -> Unit)? = null
    var onCountdownCancelled: (() -> Unit)? = null
    private var isCountingDown = false

    private val countdownRunnable = Runnable {
        isCountingDown = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        onCountdownComplete?.invoke()
    }

    fun startCountdown() {
        if (isCountingDown) return
        isCountingDown = true
        handler.postDelayed(countdownRunnable, 5 * 60 * 1000L)
    }

    fun cancelCountdown() {
        handler.removeCallbacks(countdownRunnable)
        isCountingDown = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1002, buildCountdownNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AutoStopService = this@AutoStopService
    }

    private fun buildCountdownNotification(): Notification =
        NotificationCompat.Builder(this, "classroom_controller")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("수업 자동 종료 대기중")
            .setContentText("5분 후 수업이 자동 종료됩니다")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}
