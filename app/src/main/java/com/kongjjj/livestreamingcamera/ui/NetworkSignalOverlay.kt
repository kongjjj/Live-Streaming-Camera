package com.kongjjj.livestreamingcamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.view.View
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class NetworkSignalOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f          // 縮小字體以適應 120dp 寬度
        isFakeBoldText = true
    }

    private var wifiLevel = 0
    private var sim1Level = -1
    private var sim2Level = -1

    private var pollJob: Job? = null
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPolling()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pollJob?.cancel()
    }

    private fun startPolling() {
        pollJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateSignalStrengths()
                invalidate()
                delay(2000)
            }
        }
    }

    private fun updateSignalStrengths() {
        // WiFi 強度 (0-4)
        val wifiInfo = wifiManager.connectionInfo
        wifiLevel = if (wifiInfo.networkId != -1) WifiManager.calculateSignalLevel(wifiInfo.rssi, 5) else 0

        // 檢查權限以取得行動網路強度
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sim1Level = -1
            sim2Level = -1
            return
        }

        sim1Level = -1
        sim2Level = -1
        try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (activeSubscriptionInfoList != null) {
                for (subInfo in activeSubscriptionInfoList) {
                    val tmForSub = telephonyManager.createForSubscriptionId(subInfo.subscriptionId)
                    val signalStrength = tmForSub.signalStrength
                    val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        signalStrength?.cellSignalStrengths?.firstOrNull()?.level ?: 0
                    } else {
                        @Suppress("DEPRECATION")
                        signalStrength?.level ?: 0
                    }
                    when (subInfo.simSlotIndex) {
                        0 -> sim1Level = level
                        1 -> sim2Level = level
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略異常
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        var currentY = 30f   // 垂直起始位置

        // WiFi
        canvas.drawText("WiFi", 5f, currentY, textPaint)
        drawSignalBars(canvas, 70f, currentY - 18f, wifiLevel)
        currentY += 38f

        // SIM1
        if (sim1Level >= 0) {
            canvas.drawText("SIM1", 5f, currentY, textPaint)
            drawSignalBars(canvas, 70f, currentY - 18f, sim1Level)
            currentY += 38f
        }

        // SIM2
        if (sim2Level >= 0) {
            canvas.drawText("SIM2", 5f, currentY, textPaint)
            drawSignalBars(canvas, 70f, currentY - 18f, sim2Level)
        }
    }

    private fun drawSignalBars(canvas: Canvas, startX: Float, startY: Float, level: Int) {
        val barWidth = 8f          // 寬度縮小
        val gap = 4f
        val maxBars = 4

        for (i in 0 until maxBars) {
            val isFilled = i < level
            val barHeight = 8f + (i * 6f)   // 階梯高度: 8, 14, 20, 26
            val x = startX + i * (barWidth + gap)
            val y = startY + (26f - barHeight)
            paint.color = if (isFilled) getColorForLevel(level) else Color.DKGRAY
            canvas.drawRect(x, y, x + barWidth, y + barHeight, paint)
        }
    }

    private fun getColorForLevel(level: Int): Int {
        return when (level) {
            4 -> Color.GREEN
            3 -> Color.parseColor("#90EE90")
            2 -> Color.YELLOW
            1 -> Color.parseColor("#FFA500")
            else -> Color.RED
        }
    }
}