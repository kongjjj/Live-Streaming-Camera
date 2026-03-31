package com.kongjjj.livestreamingcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MyPersistentService : Service() {

    companion object {
        private const val CHANNEL_ID = "persistent_channel"
        private const val NOTIFICATION_ID = 9999
        const val ACTION_STOP_SERVICE = "com.kongjjj.livestreamingcamera.ACTION_STOP_SERVICE"
        const val ACTION_START_STREAM = "com.kongjjj.livestreamingcamera.ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "com.kongjjj.livestreamingcamera.ACTION_STOP_STREAM"
        const val ACTION_EXIT_APP = "com.kongjjj.livestreamingcamera.ACTION_EXIT_APP"
        private const val TAG = "MyPersistentService"
    }

    private var isStreaming = false
    private val prefs by lazy { getSharedPreferences("livestream_prefs", Context.MODE_PRIVATE) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.kongjjj.livestreamingcamera.STREAM_STATE_CHANGED" -> {
                    isStreaming = intent.getBooleanExtra("is_streaming", false)
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 註冊狀態變化廣播
        val filter = IntentFilter("com.kongjjj.livestreamingcamera.STREAM_STATE_CHANGED")
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)

        // 讀取當前狀態
        isStreaming = prefs.getBoolean("is_streaming", false)
        // 啟動前台服務（首次）
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_STREAM -> {
                // 發送廣播通知 MainActivity 開始推流
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_START_STREAM))
                // 立即更新狀態（廣播會再更新一次，但先更新本地避免閃爍）
                isStreaming = true
                updateNotification()
            }
            ACTION_STOP_STREAM -> {
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STOP_STREAM))
                isStreaming = false
                updateNotification()
            }
            ACTION_EXIT_APP -> {
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_EXIT_APP))
                // 關閉服務與程式
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                // 發送退出廣播後 MainActivity 會處理 finishAffinity()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification()
        try {
            var foregroundServiceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
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
            Log.d(TAG, "前台服務已啟動")
        } catch (e: Exception) {
            Log.e(TAG, "啟動前台服務失敗", e)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        // 主意圖：點擊通知開啟主程式
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 開始/停止直播按鈕
        val streamAction = if (isStreaming) ACTION_STOP_STREAM else ACTION_START_STREAM
        val streamLabel = if (isStreaming) "停止直播" else "開始直播"
        val streamIntent = Intent(this, MyPersistentService::class.java).apply {
            action = streamAction
        }
        val streamPendingIntent = PendingIntent.getService(
            this, 1, streamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 關閉按鈕
        val stopIntent = Intent(this, MyPersistentService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("直播輔助程式")
            .setContentText(if (isStreaming) "正在直播中..." else "已停止直播")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isStreaming)      // 直播中持續顯示
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_play, streamLabel, streamPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "關閉", stopPendingIntent)
            .build()
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
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        Log.d(TAG, "背景服務已停止")
    }
}