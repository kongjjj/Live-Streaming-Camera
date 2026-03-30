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

    // 統一使用小字型畫筆（原 smallTextPaint 的大小）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f // 約12sp
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.argb(128, 0, 0, 0) // 半透明黑底
    }

    // 行距設定（可調整數值使間距更細）
    private val lineSpacing = 2f

    fun updateStats(newStats: StreamStats?) {
        stats = newStats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val stats = stats ?: return

        // 繪製半透明背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        var y = textPaint.textSize + lineSpacing

        // 顯示端點類型（使用小字體）
        canvas.drawText("${stats.endpointType.uppercase()} 統計", 10f, y, textPaint)

        y += textPaint.textSize + lineSpacing

        // 顯示位元率
        val bitrateText = String.format(Locale.US, "位元率: %d kb/s", stats.bitrateKbps)
        canvas.drawText(bitrateText, 10f, y, textPaint)

        // 如果是 SRT，顯示額外資訊
        if (stats.endpointType == "srt") {
            y += textPaint.textSize + lineSpacing
            val rttText = String.format(Locale.US, "RTT: %d ms", stats.rttMs ?: 0)
            canvas.drawText(rttText, 10f, y, textPaint)

            y += textPaint.textSize + lineSpacing
            val lossText = String.format(Locale.US, "丟包率: %.2f%%", stats.lossPercent ?: 0f)
            canvas.drawText(lossText, 10f, y, textPaint)

            y += textPaint.textSize + lineSpacing
            val sendRateText = String.format(Locale.US, "發送速率: %.2f Mbps", stats.sendRateMbps ?: 0f)
            canvas.drawText(sendRateText, 10f, y, textPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lines = if (stats?.endpointType == "srt") 5 else 2
        // 計算所需高度：行數 * (字高 + 行距) + 初始間隔
        val requiredHeight = ((lines + 1) * (textPaint.textSize + lineSpacing)).toInt()
        setMeasuredDimension(
            resolveSize(80, widthMeasureSpec), // 寬度固定 80dp（可依需要調整）
            resolveSize(requiredHeight, heightMeasureSpec)
        )
    }
}