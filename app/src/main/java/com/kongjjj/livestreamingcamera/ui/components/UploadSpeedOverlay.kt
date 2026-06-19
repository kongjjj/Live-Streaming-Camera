package com.kongjjj.livestreamingcamera.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class UploadSpeedOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var uploadSpeedKbps = 0
    private var maxBitrateKbps = 0
    private var statusText = ""

    // 用於顯示 "Max: ..." 的文字畫筆
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // 用於顯示上傳速度的畫筆（較大）
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // 用於顯示狀態文字的畫筆
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = false
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // 背景完全透明，不繪製任何矩形

    fun updateStats(maxBitrateKbps: Int, uploadSpeedKbps: Int) {
        this.maxBitrateKbps = maxBitrateKbps
        this.uploadSpeedKbps = uploadSpeedKbps
        invalidate()
    }

    fun updateStatus(status: String) {
        this.statusText = status
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val x = 10f
        // 第一行文字底部 Y 座標
        val firstLineY = labelPaint.textSize
        // 第二行文字底部 Y 座標
        val secondLineY = firstLineY + speedPaint.textSize + 6f
        // 第三行文字底部 Y 座標
        val thirdLineY = secondLineY + statusPaint.textSize + 6f

        val maxText = "Max: $maxBitrateKbps kb/s"
        canvas.drawText(maxText, x, firstLineY, labelPaint)

        // 數字顏色邏輯
        val numberColor = when {
            uploadSpeedKbps == 0 -> Color.RED
            uploadSpeedKbps in 1..1000 -> Color.parseColor("#FFA500") // 橙色
            uploadSpeedKbps in 1001..2000 -> Color.WHITE
            else -> Color.GREEN
        }

        val speedValueText = "$uploadSpeedKbps "
        val unitText = "kb/s"

        // 繪製數字
        speedPaint.color = numberColor
        canvas.drawText(speedValueText, x, secondLineY, speedPaint)

        // 繪製單位 (固定白色)
        val valueWidth = speedPaint.measureText(speedValueText)
        speedPaint.color = Color.WHITE
        canvas.drawText(unitText, x + valueWidth, secondLineY, speedPaint)

        // 繪製狀態文字
        if (statusText.isNotEmpty()) {
            canvas.drawText(statusText, x, thirdLineY, statusPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 寬度建議 120dp，高度建議 80dp（容納三行文字）
        val density = context.resources.displayMetrics.density
        val desiredWidth = (120 * density).toInt()
        val desiredHeight = (80 * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }
}
