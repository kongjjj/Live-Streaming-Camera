package com.kongjjj.livestreamingcamera

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MyPersistentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "persistent_channel"
        private const val NOTIFICATION_ID = 9999
        private const val ACTION_STOP = "com.kongjjj.livestreamingcamera.ACTION_STOP_SERVICE"
        private const val TAG = "MyPersistentService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveStreaming::WakeLock").apply {
            acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 處理關閉按鈕
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 建立關閉按鈕的 PendingIntent
        val stopIntent = Intent(this, MyPersistentService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 建立通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("直播輔助程式")
            .setContentText("正在背景維持直播連線中 (關閉螢幕亦可維持)...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "關閉", stopPendingIntent)
            .build()

        // 啟動前景服務
        try {
            var foregroundServiceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            
            // Android 14 (API 34) 以上必須包含 CAMERA 與 MICROPHONE 類型才能在背景直播
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                foregroundServiceType = foregroundServiceType or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
            Log.d(TAG, "背景服務已啟動，類型: $foregroundServiceType")
        } catch (e: Exception) {
            Log.e(TAG, "啟動前景服務失敗", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "常駐背景服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持應用程式在背景運行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        Log.d(TAG, "背景服務已停止")
    }
}