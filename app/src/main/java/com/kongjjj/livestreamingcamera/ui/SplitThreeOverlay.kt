package com.kongjjj.livestreamingcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 一開三特效的輔助線 Overlay
 * 顯示兩條幼直灰色線，標示中央 1/3 的範圍
 */
class SplitThreeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 2f // 幼線
        style = Paint.Style.STROKE
        alpha = 180 // 稍微透明，不干擾視覺
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()

        // 第一條線 (1/3 處)
        canvas.drawLine(width / 3f, 0f, width / 3f, height, paint)
        
        // 第二條線 (2/3 處)
        canvas.drawLine(width * 2f / 3f, 0f, width * 2f / 3f, height, paint)
    }
}