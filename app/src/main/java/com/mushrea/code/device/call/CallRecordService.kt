package com.mushrea.code.device.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mushrea.code.R
import java.io.File

/**
 * Foreground host of an explicit, confirmed near-end call recording. The microphone stays in use
 * only while this service runs; [ACTION_STOP] and the notification action both end it.
 */
class CallRecordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop =
        Runnable {
            CallRecordings.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.removeCallbacks(autoStop)
                CallRecordings.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                if (CallRecordings.snapshot().recording) return START_NOT_STICKY
                startInForeground()
                val destination = outputFile()
                val started =
                    runCatching { CallRecordings.start(this, destination) }
                if (started.isFailure) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
                if (seconds > 0) {
                    handler.removeCallbacks(autoStop)
                    handler.postDelayed(autoStop, seconds * 1000L)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        if (CallRecordings.snapshot().recording) CallRecordings.stop()
        super.onDestroy()
    }

    private fun outputFile(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mushrea-calls")
        if (!dir.exists()) dir.mkdirs()
        val fallback = File(filesDir, "calls")
        val root = if (dir.isDirectory || dir.mkdirs()) dir else fallback
        if (!root.isDirectory) root.mkdirs()
        return File(root, "call-${System.currentTimeMillis()}.m4a")
    }

    private fun startInForeground() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_record_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CallRecordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.call_record_ongoing_title))
                .setContentText(getString(R.string.call_record_ongoing_text))
                .setOngoing(true)
                .addAction(0, getString(R.string.call_record_action_stop), stop)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "call_record"
        private const val NOTIFICATION_ID = 4712
        const val ACTION_START = "com.mushrea.code.call.RECORD_START"
        const val ACTION_STOP = "com.mushrea.code.call.RECORD_STOP"
        const val EXTRA_SECONDS = "seconds"

        fun start(
            context: Context,
            seconds: Int,
        ) {
            val intent =
                Intent(context, CallRecordService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SECONDS, seconds)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallRecordService::class.java))
        }
    }
}
