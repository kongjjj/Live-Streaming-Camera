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
import kotlin.math.atan2
import kotlin.math.PI

class RotationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val backgroundPaint = Paint().apply {
        color = Color.GRAY
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.dpToPx(context).toFloat()
        isFakeBoldText = true
    }

    private var roll = 0f    // 左右旋轉 (-180 ~ 180)
    private var isDataValid = false

    private val smoothingAlpha = 0.2f
    private var filteredRoll = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val y = event.values[1]
            val z = event.values[2]
            
            // 計算 roll (旋轉角度)
            val rawRoll = atan2(y, z).toFloat() * 180f / PI.toFloat()
            
            // 指數平滑
            filteredRoll = smoothingAlpha * rawRoll + (1 - smoothingAlpha) * filteredRoll
            
            roll = filteredRoll
            isDataValid = true
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 畫背景 (灰色)
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        if (!isDataValid) return

        // 畫紅色粗直線刻度
        // 將 roll (-45 ~ 45) 映射到寬度
        val centerX = w / 2f
        val offsetFactor = (roll / 45f).coerceIn(-1f, 1f)
        val lineX = centerX + offsetFactor * (w / 2f)
        
        canvas.drawLine(lineX, 0f, lineX, h, linePaint)

        // 顯示旋轉角度文字 (R: %.0f°)
        val rotationText = String.format("R: %.0f°", roll)
        val textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(rotationText, 4f, textY, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 設定為 120dp x 12dp，與音量條一致
        val desiredWidth = 120.dpToPx(context)
        val desiredHeight = 12.dpToPx(context)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}
