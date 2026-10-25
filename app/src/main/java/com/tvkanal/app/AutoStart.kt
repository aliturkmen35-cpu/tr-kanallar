package com.tvkanal.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * TV açılınca Alin's'i kendiliğinden açar.
 * - TV sıfırdan açıldığında: BootReceiver
 * - TV bekleme modundan uyandığında: WakeService ekranın açılmasını dinler
 * Android'in arka plandan ekran açma kuralı gereği "diğer uygulamaların üzerinde göster"
 * izni verilmiş olmalıdır.
 */
object AutoStart {
    fun launchApp(context: Context) {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(i) }
    }

    fun startWakeService(context: Context) {
        val i = Intent(context, WakeService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoStart.startWakeService(context)
        AutoStart.launchApp(context)
    }
}

class WakeService : Service() {

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AutoStart.launchApp(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("auto", "Otomatik açılış", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "auto") else Notification.Builder(this))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Alin's")
            .setContentText("TV açıldığında Alin's kendiliğinden açılır")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
