// TiltOverlay.kt
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

class TiltOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }

    private var pitch = 0f   // 前後傾斜 (-90 ~ 90)
    private var roll = 0f    // 左右傾斜 (-90 ~ 90)
    private var isDataValid = false

    private val smoothingAlpha = 0.3f
    private var filteredPitch = 0f
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
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // 計算 pitch 和 roll（弧度轉角度）
            val rawPitch = atan2(-x, kotlin.math.sqrt(y * y + z * z)).toFloat() * 180f / PI.toFloat()
            val rawRoll = atan2(y, z).toFloat() * 180f / PI.toFloat()
            
            // 指數平滑，減少抖動
            filteredPitch = smoothingAlpha * rawPitch + (1 - smoothingAlpha) * filteredPitch
            filteredRoll = smoothingAlpha * rawRoll + (1 - smoothingAlpha) * filteredRoll
            
            pitch = filteredPitch.coerceIn(-90f, 90f)
            roll = filteredRoll.coerceIn(-90f, 90f)
            isDataValid = true
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDataValid) {
            canvas.drawText("等待數據", 10f, 30f, textPaint)
            return
        }
        
        // 顯示傾斜角度（一行顯示 pitch/roll）
        val tiltText = String.format("P:%.0f° R:%.0f°", pitch, roll)
        canvas.drawText(tiltText, 10f, 30f, textPaint)
        
        // 可選：簡單的水平儀圖形（進階可加）
        // 此處顯示一個小圓點代表水平偏移位置（可選）
        val centerX = width / 2f
        val centerY = height / 2f + 10f
        val radius = 12f
        // 將 pitch（前後）映射為 Y 軸偏移，roll 映射為 X 軸偏移（限制範圍內）
        val offsetX = (roll / 45f).coerceIn(-1f, 1f) * (width / 2 - radius - 10)
        val offsetY = (pitch / 45f).coerceIn(-1f, 1f) * (height / 2 - radius - 10)
        canvas.drawCircle(centerX + offsetX, centerY + offsetY, radius, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 100.dpToPx(context)
        val desiredHeight = 70.dpToPx(context)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}