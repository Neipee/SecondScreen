package com.secondscreen.app.cursor

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.secondscreen.app.R
import kotlin.math.hypot

/** Draggable round floating button on the phone screen. Tap toggles the touchpad. */
internal class BubbleOverlay(
    private val service: CursorAccessibilityService,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
) {
    private val density = service.resources.displayMetrics.density
    private val sizePx = (56f * density).toInt()
    private val marginPx = (8f * density).toInt()
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()

    var isShowing: Boolean = false
        private set

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "SecondScreenBubble"
        val b = service.phoneBounds()
        x = b.width() - sizePx - marginPx
        y = b.height() / 3
    }

    private val view: TextView = TextView(service).apply {
        text = "🖱️"
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xB33F51B5.toInt())
            setStroke((2f * density).toInt(), 0xCCFFFFFF.toInt())
        }
        contentDescription = service.getString(R.string.cursor_bubble_desc)
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        installTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchListener() {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampInside()
                        applyLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge()
                    } else {
                        v.performClick()
                        onTap()
                    }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampInside() {
        val b = service.phoneBounds()
        val maxX = (b.width() - sizePx).coerceAtLeast(0)
        val maxY = (b.height() - sizePx).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun snapToEdge() {
        val b = service.phoneBounds()
        params.x = if (params.x + sizePx / 2 < b.width() / 2) {
            marginPx
        } else {
            (b.width() - sizePx - marginPx).coerceAtLeast(0)
        }
        val maxY = (b.height() - sizePx - marginPx).coerceAtLeast(marginPx)
        params.y = params.y.coerceIn(marginPx, maxY)
        applyLayout()
    }

    private fun applyLayout() {
        if (!isShowing) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Bubble update failed", t)
        }
    }

    /** Keep the bubble on screen after a rotation / resolution change. */
    fun keepInside() {
        if (!isShowing) return
        snapToEdge()
    }

    fun show() {
        if (isShowing) return
        try {
            clampInside()
            windowManager.addView(view, params)
            isShowing = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to show bubble", t)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to remove bubble", t)
        }
    }

    /** Re-add so it sits above windows added later (e.g. the touchpad panel). */
    fun bringToFront() {
        if (!isShowing) return
        hide()
        show()
    }

    private companion object {
        const val TAG = "SecondScreen"
    }
}
