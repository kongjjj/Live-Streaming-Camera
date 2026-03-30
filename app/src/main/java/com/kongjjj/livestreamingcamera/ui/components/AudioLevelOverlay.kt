package com.kongjjj.livestreamingcamera.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.kongjjj.livestreamingcamera.audio.AudioLevel

class AudioLevelOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint().apply { color = Color.GRAY }
    private val leftBarPaint = Paint()
    private val rightBarPaint = Paint()

    private var leftLevel = 0f
    private var rightLevel = 0f

    init {
        alpha = 0.8f
    }

    fun updateAudioLevel(audioLevel: AudioLevel) {
        leftLevel = audioLevel.normalizedLevel
        rightLevel = audioLevel.normalizedLevelRight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val leftWidth = leftLevel * width * 0.45f
        val rightWidth = rightLevel * width * 0.45f

        leftBarPaint.color = getColorForLevel(leftLevel)
        canvas.drawRect(0f, 0f, leftWidth, height.toFloat(), leftBarPaint)

        rightBarPaint.color = getColorForLevel(rightLevel)
        canvas.drawRect(width * 0.55f, 0f, width * 0.55f + rightWidth, height.toFloat(), rightBarPaint)
    }

    private fun getColorForLevel(level: Float): Int {
        return when {
            level > 0.8f -> Color.RED
            level > 0.6f -> Color.YELLOW
            level > 0.3f -> Color.GREEN
            else -> Color.GRAY
        }
    }
}