package com.mariaray.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class FilterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var jobId = 0L

    private val channelId = "mariaray_filter"
    private val notifId = 1001

    companion object {
        const val ACTION_START = "com.mariaray.app.START"
        const val ACTION_STOP = "com.mariaray.app.STOP"
        const val EXTRA_INPUT = "input"
        const val BROADCAST_PROGRESS = "com.mariaray.app.PROGRESS"
        const val BROADCAST_DONE = "com.mariaray.app.DONE"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_CUR = "cur"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_MSG = "msg"
        const val EXTRA_RESULT_PATH = "result_path"
        const val EXTRA_ERROR = "error"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(channelId, "Filter", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, FilterService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPi = if (openIntent != null) PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else null

        val b = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("MariaRay")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)

        if (openPi != null) b.setContentIntent(openPi)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(notifId, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun goForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, notif)
        }
    }

    private fun broadcastProgress(stage: String, cur: Int, total: Int, msg: String) {
        val i = Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_CUR, cur)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_MSG, msg)
        }
        sendBroadcast(i)
    }

    private fun broadcastDone(resultPath: String?, error: String?) {
        val i = Intent(BROADCAST_DONE).apply {
            setPackage(packageName)
            if (error != null) putExtra(EXTRA_ERROR, error)
            else if (resultPath != null) putExtra(EXTRA_RESULT_PATH, resultPath)
        }
        sendBroadcast(i)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFiltering()
            return START_NOT_STICKY
        }

        val input = intent?.getStringExtra(EXTRA_INPUT) ?: ""
        goForeground("Starting...")

        if (input.isBlank()) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        val myId = ++jobId
        job = scope.launch {
            var error: String? = null
            var resultPath: String? = null
            try {
                val outcome = FilterEngine.run(this@FilterService, input) { stage, cur, total, msg ->
                    updateNotification("$stage: $cur/$total — $msg")
                    broadcastProgress(stage, cur, total, msg)
                }
                val outFile = File(filesDir, "best.txt")
                outFile.writeText(outcome.bestContent)
                resultPath = outFile.absolutePath
            } catch (e: CancellationException) {
                error = "Cancelled"
                throw e
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                broadcastDone(resultPath, error)
                if (myId == jobId) {
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopFiltering() {
        job?.cancel()
        FilterEngine.stopAll()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
    }
}
