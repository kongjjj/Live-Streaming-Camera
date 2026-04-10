// ShakeLevelOverlay.kt
package com.kongjjj.livestreamingcamera.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

class ShakeLevelOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val backgroundPaint = Paint().apply { color = Color.DKGRAY }
    private val barPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }

    private var shakeLevel = 0f  // 0..1 之間的抖動強度
    private var smoothingAlpha = 0.3f  // 平滑係數

    // 加速度低通濾波
    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var alpha = 0.8f  // 低通濾波常數

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // 低通濾波，去除瞬間雜訊
            filteredX = alpha * filteredX + (1 - alpha) * event.values[0]
            filteredY = alpha * filteredY + (1 - alpha) * event.values[1]
            filteredZ = alpha * filteredZ + (1 - alpha) * event.values[2]

            // 計算加速度向量的大小（去除重力影響，僅考慮動態加速度）
            val magnitude = sqrt(
                (event.values[0] - filteredX) * (event.values[0] - filteredX) +
                (event.values[1] - filteredY) * (event.values[1] - filteredY) +
                (event.values[2] - filteredZ) * (event.values[2] - filteredZ)
            )

            // 將 magnitude 映射到 0..1 範圍（通常抖動在 0~5 之間，超過 2 就算大）
            val rawLevel = (magnitude / 2f).coerceIn(0f, 1f)
            // 指數平滑
            shakeLevel = smoothingAlpha * rawLevel + (1 - smoothingAlpha) * shakeLevel
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 背景
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // 抖動條
        val barWidth = w * shakeLevel
        barPaint.color = when {
            shakeLevel < 0.3f -> Color.GREEN
            shakeLevel < 0.7f -> Color.YELLOW
            else -> Color.RED
        }
        canvas.drawRect(0f, 0f, barWidth, h, barPaint)

        // 文字顯示百分比
        val percent = (shakeLevel * 100).toInt()
        val text = "$percent%"
        val textX = w - textPaint.measureText(text) - 4f
        val textY = (h + textPaint.textSize) / 2f
        canvas.drawText(text, textX, textY, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 120.dpToPx(context)
        val desiredHeight = 12.dpToPx(context)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}