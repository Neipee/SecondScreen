package com.secondscreen.app.cursor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Laptop-style touchpad surface:
 * one-finger drag = move, tap = click, double tap = double click,
 * long press (no movement) = long click, two-finger drag = scroll.
 */
@SuppressLint("ViewConstructor")
internal class TouchpadView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onTap()
        fun onDoubleTap()
        fun onLongPress()
        /** Finger direction of a two-finger scroll step: one of (±1, 0) or (0, ±1). */
        fun onScroll(dirX: Float, dirY: Float)
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scrollStepPx = 32f * density
    private val accelRefPx = 10f * density

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var longPressFired = false
    private var tracking = false

    private var lastCx = 0f
    private var lastCy = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var lastScrollEmit = 0L

    private var tapPending = false

    private val longPressRunnable = Runnable {
        if (tracking && !moved && !multi) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }

    private val singleTapRunnable = Runnable {
        tapPending = false
        listener.onTap()
    }

    init {
        background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(0x22FFFFFF)
            setStroke((1f * density).toInt().coerceAtLeast(1), 0x55FFFFFF)
        }
        isClickable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                multi = false
                longPressFired = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    multi = true
                    removeCallbacks(longPressRunnable)
                    lastCx = (event.getX(0) + event.getX(1)) / 2f
                    lastCy = (event.getY(0) + event.getY(1)) / 2f
                    scrollAccX = 0f
                    scrollAccY = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (multi) {
                    if (event.pointerCount >= 2) handleScrollMove(event)
                } else {
                    handleSingleMove(event)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val quick = event.eventTime - downTime < TAP_MAX_MS
                if (tracking && !multi && !moved && !longPressFired && quick) {
                    performClick()
                    if (tapPending) {
                        removeCallbacks(singleTapRunnable)
                        tapPending = false
                        listener.onDoubleTap()
                    } else {
                        tapPending = true
                        postDelayed(singleTapRunnable, DOUBLE_TAP_MS)
                    }
                }
                tracking = false
                multi = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                tracking = false
                multi = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleSingleMove(event: MotionEvent) {
        val x = event.x
        val y = event.y
        if (!moved && hypot(x - downX, y - downY) > touchSlop) {
            moved = true
            removeCallbacks(longPressRunnable)
        }
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        if (!moved || longPressFired) return
        val speed = hypot(dx, dy)
        val factor = BASE_SENSITIVITY * (1f + ACCEL_GAIN * min(speed / accelRefPx, 1f))
        listener.onMove(dx * factor, dy * factor)
    }

    private fun handleScrollMove(event: MotionEvent) {
        val cx = (event.getX(0) + event.getX(1)) / 2f
        val cy = (event.getY(0) + event.getY(1)) / 2f
        scrollAccX += cx - lastCx
        scrollAccY += cy - lastCy
        lastCx = cx
        lastCy = cy
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollEmit < SCROLL_MIN_INTERVAL_MS) return
        if (abs(scrollAccY) >= scrollStepPx && abs(scrollAccY) >= abs(scrollAccX)) {
            listener.onScroll(0f, if (scrollAccY > 0) 1f else -1f)
            scrollAccX = 0f
            scrollAccY = 0f
            lastScrollEmit = now
        } else if (abs(scrollAccX) >= scrollStepPx) {
            listener.onScroll(if (scrollAccX > 0) 1f else -1f, 0f)
            scrollAccX = 0f
            scrollAccY = 0f
            lastScrollEmit = now
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(singleTapRunnable)
        tapPending = false
        tracking = false
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
        const val DOUBLE_TAP_MS = 250L
        const val TAP_MAX_MS = 300L
        const val SCROLL_MIN_INTERVAL_MS = 230L
        const val BASE_SENSITIVITY = 1.5f
        const val ACCEL_GAIN = 0.67f
    }
}
