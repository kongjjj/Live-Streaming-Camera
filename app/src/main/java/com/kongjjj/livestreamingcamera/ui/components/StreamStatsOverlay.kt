package com.kongjjj.livestreamingcamera.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.kongjjj.livestreamingcamera.StreamStats
import java.util.Locale

class StreamStatsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var stats: StreamStats? = null

    // 文字畫筆
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f // 約 15sp
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // 黑色半透明背景（可選，若要純文字可移除）
    private val backgroundPaint = Paint().apply {
        color = Color.argb(128, 0, 0, 0)
    }

    fun updateStats(newStats: StreamStats?) {
        stats = newStats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val stats = stats ?: return
        val currentKbps = stats.bitrateKbps
        val maxKbps = stats.maxBitrateKbps

        // 轉換為 Mbps (除以 1000)
        val currentMbps = currentKbps / 1000.0
        val maxMbps = maxKbps / 1000.0

        // 格式化文字 (保留兩位小數)
        val text = String.format(Locale.US, "%.2f / %.1f Mbps", currentMbps, maxMbps)

        // 根據當前碼率決定顏色
        textPaint.color = when {
            currentMbps < 1.0 -> Color.RED
            currentMbps > 2.0 -> Color.GREEN
            else -> Color.YELLOW
        }

        // 繪製半透明背景 (可選，若不需要可註解掉)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 繪製文字，置中或靠左可調整
        val x = 10f
        val y = (height + textPaint.textSize) / 2f
        canvas.drawText(text, x, y, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 寬度約 160dp，高度約 40dp
        val desiredWidth = (160 * context.resources.displayMetrics.density).toInt()
        val desiredHeight = (40 * context.resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }
}