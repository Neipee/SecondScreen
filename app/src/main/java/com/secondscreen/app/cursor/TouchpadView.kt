package com.secondscreen.app.cursor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Laptop-style touchpad surface:
 * one-finger drag = move, tap = click, double tap = double click,
 * long press (no movement) = long click, two-finger drag = scroll, two-finger pinch = zoom.
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
        /** Two-finger gesture recognised as a pinch (distance change dominates). */
        fun onPinchStart()
        /** Cumulative scale = current finger distance / start distance (> 1 = fingers spread). */
        fun onPinchUpdate(scale: Float)
        /** One of the two fingers was lifted; [scale] is the final cumulative scale. */
        fun onPinchEnd(scale: Float)
        fun onPinchCancel()
    }

    private enum class TwoFinger { NONE, UNDECIDED, SCROLL, PINCH, DONE }

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

    private val pinchThresholdPx = max(40f, 12f * density)
    private var twoMode = TwoFinger.NONE
    private var pidA = MotionEvent.INVALID_POINTER_ID
    private var pidB = MotionEvent.INVALID_POINTER_ID
    private var startDist = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var lastScale = 1f

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
        try {
            handleTouch(event)
        } catch (t: Throwable) {
            Log.w(TAG, "Touchpad event handling failed", t)
        }
        return true
    }

    private fun handleTouch(event: MotionEvent) {
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
                twoMode = TwoFinger.NONE
                pidA = MotionEvent.INVALID_POINTER_ID
                pidB = MotionEvent.INVALID_POINTER_ID
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                removeCallbacks(longPressRunnable)
                // Only the first two fingers form the pair; extra fingers are ignored.
                if (tracking && twoMode == TwoFinger.NONE && event.pointerCount >= 2) {
                    pidA = event.getPointerId(0)
                    pidB = event.getPointerId(1)
                    startTwoFinger(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                if (multi) {
                    handleTwoFingerMove(event)
                } else {
                    handleSingleMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (idx in 0 until event.pointerCount) {
                    val upId = event.getPointerId(idx)
                    if (upId == pidA || upId == pidB) endTwoFinger()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                endTwoFinger()
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
                twoMode = TwoFinger.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelPinch()
                tracking = false
                multi = false
                twoMode = TwoFinger.NONE
            }
        }
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

    private fun startTwoFinger(event: MotionEvent) {
        val ia = event.findPointerIndex(pidA)
        val ib = event.findPointerIndex(pidB)
        if (ia < 0 || ib < 0) {
            twoMode = TwoFinger.DONE
            return
        }
        val ax = event.getX(ia)
        val ay = event.getY(ia)
        val bx = event.getX(ib)
        val by = event.getY(ib)
        startDist = hypot(ax - bx, ay - by)
        startMidX = (ax + bx) / 2f
        startMidY = (ay + by) / 2f
        lastCx = startMidX
        lastCy = startMidY
        scrollAccX = 0f
        scrollAccY = 0f
        lastScale = 1f
        twoMode = TwoFinger.UNDECIDED
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        if (twoMode != TwoFinger.UNDECIDED && twoMode != TwoFinger.SCROLL && twoMode != TwoFinger.PINCH) return
        val ia = event.findPointerIndex(pidA)
        val ib = event.findPointerIndex(pidB)
        if (ia < 0 || ib < 0) return
        val ax = event.getX(ia)
        val ay = event.getY(ia)
        val bx = event.getX(ib)
        val by = event.getY(ib)
        val dist = hypot(ax - bx, ay - by)
        val midX = (ax + bx) / 2f
        val midY = (ay + by) / 2f
        when (twoMode) {
            TwoFinger.UNDECIDED -> {
                val distChange = abs(dist - startDist)
                val midMove = hypot(midX - startMidX, midY - startMidY)
                if (distChange > pinchThresholdPx && distChange > midMove && startDist > 1f) {
                    twoMode = TwoFinger.PINCH
                    lastScale = dist / startDist
                    listener.onPinchStart()
                    listener.onPinchUpdate(lastScale)
                } else if (midMove > touchSlop && midMove >= distChange) {
                    twoMode = TwoFinger.SCROLL
                    handleScrollMove(midX, midY)
                }
            }

            TwoFinger.SCROLL -> handleScrollMove(midX, midY)

            TwoFinger.PINCH -> {
                val scale = if (startDist > 1f) (dist / startDist).coerceIn(MIN_SCALE, MAX_SCALE) else 1f
                if (abs(scale - lastScale) >= 0.005f) {
                    lastScale = scale
                    listener.onPinchUpdate(scale)
                }
            }

            else -> Unit
        }
    }

    /** One of the two tracked fingers was lifted: finish the pinch, ignore the rest of the gesture. */
    private fun endTwoFinger() {
        val wasPinch = twoMode == TwoFinger.PINCH
        if (twoMode != TwoFinger.NONE) twoMode = TwoFinger.DONE
        pidA = MotionEvent.INVALID_POINTER_ID
        pidB = MotionEvent.INVALID_POINTER_ID
        if (wasPinch) listener.onPinchEnd(lastScale)
    }

    private fun cancelPinch() {
        val wasPinch = twoMode == TwoFinger.PINCH
        twoMode = TwoFinger.NONE
        pidA = MotionEvent.INVALID_POINTER_ID
        pidB = MotionEvent.INVALID_POINTER_ID
        if (wasPinch) listener.onPinchCancel()
    }

    private fun handleScrollMove(cx: Float, cy: Float) {
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
        try {
            cancelPinch()
        } catch (t: Throwable) {
            Log.w(TAG, "Pinch cancel failed", t)
        }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
        const val DOUBLE_TAP_MS = 250L
        const val TAP_MAX_MS = 300L
        const val SCROLL_MIN_INTERVAL_MS = 230L
        const val BASE_SENSITIVITY = 1.5f
        const val ACCEL_GAIN = 0.67f
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
        const val TAG = "SecondScreen"
    }
}
