package com.kongjjj.livestreamingcamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.*

class NetworkSignalOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        isFakeBoldText = true
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
    }

    private var wifiLevel = 0
    private var sim1Level = -1
    private var sim2Level = -1
    private var activeNetworkType: String? = null  // "wifi", "cellular"
    private var activeSimSlot: Int? = null         // 0 for SIM1, 1 for SIM2

    private var pollJob: Job? = null
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
                updateActiveNetwork()
                invalidate()
                delay(2000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSignalStrengths() {
        val wifiInfo = wifiManager.connectionInfo
        wifiLevel = if (wifiInfo.networkId != -1) WifiManager.calculateSignalLevel(wifiInfo.rssi, 5) else 0

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
                    val level = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun updateActiveNetwork() {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                activeNetworkType = "wifi"
                activeSimSlot = null
            }
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                activeNetworkType = "cellular"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
                    val subInfo = subscriptionManager.getActiveSubscriptionInfo(defaultDataSubId)
                    activeSimSlot = subInfo?.simSlotIndex
                } else {
                    activeSimSlot = 0
                }
            }
            else -> {
                activeNetworkType = null
                activeSimSlot = null
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var currentY = 30f

        // WiFi
        canvas.drawText("WiFi", 5f, currentY, textPaint)
        drawSignalBars(canvas, currentY - 18f, wifiLevel)
        if (activeNetworkType == "wifi") {
            drawIndicator(canvas, currentY - 18f)
        }
        currentY += 38f

        // SIM1
        if (sim1Level >= 0) {
            canvas.drawText("SIM1", 5f, currentY, textPaint)
            drawSignalBars(canvas, currentY - 18f, sim1Level)
            if (activeNetworkType == "cellular" && activeSimSlot == 0) {
                drawIndicator(canvas, currentY - 18f)
            }
            currentY += 38f
        }

        // SIM2
        if (sim2Level >= 0) {
            canvas.drawText("SIM2", 5f, currentY, textPaint)
            drawSignalBars(canvas, currentY - 18f, sim2Level)
            if (activeNetworkType == "cellular" && activeSimSlot == 1) {
                drawIndicator(canvas, currentY - 18f)
            }
        }
    }

    private fun drawSignalBars(canvas: Canvas, baseY: Float, level: Int) {
        val startX = 70f
        val barWidth = 8f
        val gap = 4f
        val maxBars = 4

        for (i in 0 until maxBars) {
            val isFilled = i < level
            val barHeight = 8f + (i * 6f)
            val x = startX + i * (barWidth + gap)
            val y = baseY + (26f - barHeight)
            paint.color = if (isFilled) getColorForLevel(level) else Color.DKGRAY
            canvas.drawRect(x, y, x + barWidth, y + barHeight, paint)
        }
    }

    private fun drawIndicator(canvas: Canvas, baseY: Float) {
        val startX = 70f + 4 * (8f + 4f) + 4f
        val radius = 5f
        val centerX = startX + radius
        val centerY = baseY + 13f
        canvas.drawCircle(centerX, centerY, radius, indicatorPaint)
    }

    private fun getColorForLevel(level: Int): Int {
        return when (level) {
            4 -> Color.GREEN
            3 -> "#90EE90".toColorInt()
            2 -> Color.YELLOW
            1 -> "#FFA500".toColorInt()
            else -> Color.RED
        }
    }
}