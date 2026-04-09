package com.kongjjj.livestreamingcamera.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View

class BatteryOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
    }

    private var level = 0
    private var isCharging = false
    private var temperature = 0f
    private var voltage = 0

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (scale > 0) level = (level * 100 / scale).coerceIn(0, 100)

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL)

                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(batteryReceiver)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 電池圖示
        val batteryWidth = 80f
        val batteryHeight = 30f
        val startX = 10f
        val startY = 10f

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(startX, startY, startX + batteryWidth, startY + batteryHeight, paint)

        // 正極頭
        val tipWidth = 6f
        val tipHeight = 16f
        canvas.drawRect(startX + batteryWidth, startY + (batteryHeight - tipHeight) / 2,
            startX + batteryWidth + tipWidth, startY + (batteryHeight + tipHeight) / 2, paint)

        // 電量條
        paint.style = Paint.Style.FILL
        val fillPercent = level / 100f
        val fillWidth = (batteryWidth - 6) * fillPercent
        val fillColor = when {
            level <= 15 -> Color.RED
            level <= 50 -> Color.YELLOW
            else -> Color.GREEN
        }
        paint.color = fillColor
        canvas.drawRect(startX + 3, startY + 3, startX + 3 + fillWidth, startY + batteryHeight - 3, paint)

        // 計算 1dp 間距的像素值
        val spacingPx = 1.dpToPx(context).toFloat()

        // 取得文字度量
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val smallFm = smallTextPaint.fontMetrics
        val smallTextHeight = smallFm.descent - smallFm.ascent

        // 電池圖示與文字之間的間距（4dp）
        val afterBatterySpacing = 4.dpToPx(context).toFloat()
        var currentY = startY + batteryHeight + afterBatterySpacing - fm.ascent

        // 第一行：電量
        val chargeSymbol = if (isCharging) "⚡" else ""
        val levelText = "$level% $chargeSymbol"
        canvas.drawText(levelText, startX, currentY, textPaint)

        // 第二行：溫度，間距 1dp
        currentY += textHeight + spacingPx
        val tempText = String.format("%.1f°C", temperature)
        canvas.drawText(tempText, startX, currentY, smallTextPaint)

        // 第三行：電壓或充電狀態，間距 1dp
        currentY += smallTextHeight + spacingPx
        if (voltage > 0 && !isCharging) {
            val voltageText = String.format("%.2fV", voltage / 1000f)
            canvas.drawText(voltageText, startX, currentY, smallTextPaint)
        } else if (isCharging) {
            canvas.drawText("充電中", startX, currentY, smallTextPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 200.dpToPx(context)
        val desiredHeight = 100.dpToPx(context)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}