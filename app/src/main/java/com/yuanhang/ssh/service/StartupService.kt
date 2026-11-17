package com.yuanhang.ssh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yuanhang.ssh.MainActivity
import com.yuanhang.ssh.R

/**
 * Foreground service displayed while at least one remote session is open.
 *
 * Without this, Android is free to kill the process a few minutes after the app moves
 * to the background, taking every SSH connection with it.
 */
class StartupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(1))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_SESSION_COUNT, 1) ?: 1
        notifySessions(count)
        return START_STICKY
    }

    private fun notifySessions(count: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在保持 $count 个远程会话在线")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_sessions),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "remote_sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_SESSION_COUNT = "session_count"

        fun start(context: Context, sessionCount: Int) {
            val intent = Intent(context, StartupService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, sessionCount: Int) {
            val intent = Intent(context, StartupService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StartupService::class.java))
        }
    }
}
