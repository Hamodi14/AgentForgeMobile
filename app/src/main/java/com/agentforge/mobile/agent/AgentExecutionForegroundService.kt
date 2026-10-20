package com.agentforge.mobile.agent

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
import com.agentforge.mobile.MainActivity
import com.agentforge.mobile.R

/**
 * Android Foreground Service (`foregroundServiceType="dataSync"`) that displays an ongoing
 * notification while a coding task is actively running, allowing the user to tap the notification
 * to return directly to the active task screen.
 *
 * Respects Android platform background limits and does not claim immunity to Force Stop or reboot;
 * interrupted sessions are recovered via Room `TaskSessionEntity` checkpoints on next launch.
 */
class AgentExecutionForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "agentforge_execution_channel"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_PROJECT_NAME = "extra_project_name"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun startOrUpdate(context: Context, projectName: String, statusText: String) {
            val intent = Intent(context, AgentExecutionForegroundService::class.java).apply {
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentExecutionForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: "AgentForge Project"
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Executing coding task..."

        val notification = buildNotification(projectName, statusText)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun buildNotification(projectName: String, statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AgentForge: $projectName")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AgentForge Active Coding Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while AgentForge Mobile is executing coding steps."
            }
            mgr.createNotificationChannel(channel)
        }
    }
}
