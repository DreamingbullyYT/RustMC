package dev.horizon.launcher.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that only exists while an on-device HNMod build runs. The build itself stays in
 * [EmbeddedToolchain]; this keeps Android from freezing or killing the app's PRoot/Cargo child processes
 * when the user switches away from Horizon (a wake lock keeps the CPU on, but not the process alive).
 */
class HnmodBuildService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HNMod compiler", NotificationManager.IMPORTANCE_LOW)
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Compiling HNMods")
            .setContentText("Horizon is building your native mods. Tap to return.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "hnmod-build"
        private const val NOTIFICATION_ID = 2

        /** Call while Horizon is visible (the user just confirmed the build). Never fails the build. */
        fun begin(context: Context) {
            runCatching { context.startForegroundService(Intent(context, HnmodBuildService::class.java)) }
        }

        fun end(context: Context) {
            runCatching { context.stopService(Intent(context, HnmodBuildService::class.java)) }
        }
    }
}
