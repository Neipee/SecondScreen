package com.secondscreen.app.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.ceil

/** Classic arrow mouse pointer: white fill, black outline, ~24dp tall. Tip at (tipOffset, tipOffset). */
internal class PointerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val sizePx = 24f * density
    private val strokePx = 1.5f * density
    private val unit = sizePx / 20f

    /** Distance in px from the view's top-left corner to the arrow tip. */
    val tipOffset: Int = ceil(strokePx).toInt() + 1

    val viewWidth: Int = ceil(12f * unit).toInt() + tipOffset * 2
    val viewHeight: Int = ceil(20f * unit).toInt() + tipOffset * 2

    private val path = Path().apply {
        val o = tipOffset.toFloat()
        moveTo(o, o)
        lineTo(o, o + 17f * unit)
        lineTo(o + 4f * unit, o + 13f * unit)
        lineTo(o + 7f * unit, o + 20f * unit)
        lineTo(o + 10f * unit, o + 19f * unit)
        lineTo(o + 7f * unit, o + 12f * unit)
        lineTo(o + 12f * unit, o + 12f * unit)
        close()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = strokePx
        strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewWidth, viewHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}
