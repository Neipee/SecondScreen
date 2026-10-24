package com.secondscreen.app.cursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.secondscreen.app.DisplayPrefs
import com.secondscreen.app.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Accessibility service providing:
 *  - a floating bubble + touchpad panel on the phone to drive a cursor on the external display;
 *  - (Android 14+) a "physical mouse" mode where the mouse moves across phone and external display
 *    as one extended desktop (external display is to the right of the phone).
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SecondScreen"

        @Volatile
        var instance: CursorAccessibilityService? = null
            private set

        private const val TAP_MS = 50L
        private const val DOUBLE_TAP_GAP_MS = 120L
        private const val LONG_PRESS_MS = 700L
        private const val SCROLL_MS = 200L
        private const val SCROLL_PX = 300f
        private const val DRAG_THRESHOLD_PX = 20f
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private var connected = false

    private var bubble: BubbleOverlay? = null
    private var panel: TouchpadPanel? = null
    private var phoneCursor: CursorOverlay? = null
    private var externalCursor: CursorOverlay? = null
    private var externalDisplayId = Display.INVALID_DISPLAY

    // Virtual cursor (shared by touchpad and mouse mode).
    private var curDisplayId = Display.INVALID_DISPLAY
    private var curX = 0f
    private var curY = 0f

    internal var isMouseModeActive = false
        private set

    // Physical mouse state.
    private var lastEventX = Float.NaN
    private var lastEventY = Float.NaN
    private var primaryDown = false
    private var pressDisplayId = Display.INVALID_DISPLAY
    private var pressX = 0f
    private var pressY = 0f
    private var pendingScrollV = 0f
    private var pendingScrollH = 0f
    private var lastScrollAt = 0L
    private var scrollFlushPosted = false
    private val flushScrollRunnable = Runnable {
        scrollFlushPosted = false
        flushWheelScroll()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplaysChanged()
        override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged()
        override fun onDisplayChanged(displayId: Int) {
            if (!connected) return
            safe("onDisplayChanged") {
                if (displayId == externalDisplayId) externalCursor?.refreshBounds()
                if (displayId == Display.DEFAULT_DISPLAY) {
                    phoneCursor?.refreshBounds()
                    bubble?.keepInside()
                    panel?.relayout()
                }
                if (displayId == curDisplayId) {
                    clampCursor()
                    updateCursorOverlays()
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            connected = true
            instance = this
            displayManager.registerDisplayListener(displayListener, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "Service setup failed", t)
        }
        refreshFromPrefs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used.
    }

    override fun onInterrupt() {
        // Not used.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        val wasConnected = connected
        connected = false
        handler.removeCallbacksAndMessages(null)
        if (::displayManager.isInitialized) {
            try {
                displayManager.unregisterDisplayListener(displayListener)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterDisplayListener failed", t)
            }
        }
        if (wasConnected && Build.VERSION.SDK_INT >= 34) {
            setMouseCapture(false)
        }
        isMouseModeActive = false
        primaryDown = false
        safe("teardown overlays") {
            bubble?.hide()
            panel?.hide()
            phoneCursor?.remove()
            externalCursor?.remove()
        }
        bubble = null
        panel = null
        phoneCursor = null
        externalCursor = null
        externalDisplayId = Display.INVALID_DISPLAY
        curDisplayId = Display.INVALID_DISPLAY
    }

    // ------------------------------------------------------------------ public API

    /** Shows the touchpad panel on the phone. */
    fun openTouchpad() {
        if (!isMainThread()) {
            handler.post { openTouchpad() }
            return
        }
        if (!connected) return
        safe("openTouchpad") {
            val p = panel ?: TouchpadPanel(this, windowManager).also { panel = it }
            if (!p.isShowing) {
                p.show()
                bubble?.bringToFront()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                if (externalCursor == null) rebuildExternal()
                if (!(isMouseModeActive && curDisplayId == Display.DEFAULT_DISPLAY) &&
                    curDisplayId != externalDisplayId && externalDisplayId != Display.INVALID_DISPLAY
                ) {
                    centerOn(externalDisplayId)
                }
            }
            updateCursorOverlays()
            p.updateStatus()
        }
    }

    /** Re-reads DisplayPrefs (bubble, mouse mode, target display) and updates overlays. */
    fun refreshFromPrefs() {
        if (!isMainThread()) {
            handler.post { refreshFromPrefs() }
            return
        }
        if (!connected) return
        safe("refreshFromPrefs") {
            rebuildExternal()
            applyMouseMode(DisplayPrefs.isMouseModeEnabled(this))
            if (DisplayPrefs.isBubbleEnabled(this)) {
                val b = bubble ?: BubbleOverlay(this, windowManager) { toggleTouchpad() }.also { bubble = it }
                b.show()
            } else {
                bubble?.hide()
            }
            if (curDisplayId == Display.INVALID_DISPLAY) {
                if (externalDisplayId != Display.INVALID_DISPLAY) {
                    centerOn(externalDisplayId)
                } else if (isMouseModeActive) {
                    centerOn(Display.DEFAULT_DISPLAY)
                }
            }
            updateCursorOverlays()
            panel?.updateStatus()
        }
    }

    // ------------------------------------------------------------------ panel actions (internal)

    internal fun closeTouchpad() {
        safe("closeTouchpad") {
            panel?.hide()
            updateCursorOverlays()
        }
    }

    private fun toggleTouchpad() {
        if (panel?.isShowing == true) closeTouchpad() else openTouchpad()
    }

    internal fun touchpadMove(dx: Float, dy: Float) {
        if (!connected || !ensureTouchpadCursor()) return
        safe("touchpadMove") { moveCursorBy(dx, dy, allowCross = isMouseModeActive) }
    }

    internal fun clickAtCursor() {
        if (!connected || !ensureTouchpadCursor()) return
        tap(curDisplayId, curX, curY, TAP_MS, "click")
    }

    internal fun doubleClickAtCursor() {
        if (!connected || !ensureTouchpadCursor()) return
        val x = max(0f, curX)
        val y = max(0f, curY)
        val first = GestureDescription.StrokeDescription(pointPath(x, y), 0L, TAP_MS)
        val second = GestureDescription.StrokeDescription(pointPath(x, y), TAP_MS + DOUBLE_TAP_GAP_MS, TAP_MS)
        dispatchOn(curDisplayId, listOf(first, second), "double-click")
    }

    internal fun longPressAtCursor() {
        if (!connected || !ensureTouchpadCursor()) return
        tap(curDisplayId, curX, curY, LONG_PRESS_MS, "long-press")
    }

    /** dirX/dirY = finger direction (natural scrolling: content follows the fingers). */
    internal fun touchpadScroll(dirX: Float, dirY: Float) {
        if (!connected || !ensureTouchpadCursor()) return
        scrollAt(curDisplayId, dirX * SCROLL_PX, dirY * SCROLL_PX)
    }

    internal fun goBack() {
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) Log.w(TAG, "GLOBAL_ACTION_BACK failed")
    }

    internal fun goHome() {
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) Log.w(TAG, "GLOBAL_ACTION_HOME failed")
    }

    internal fun toggleMouseModeFromPanel() {
        if (Build.VERSION.SDK_INT < 34) {
            panel?.showMessage(getString(R.string.cursor_mouse_requires_android_14))
            return
        }
        DisplayPrefs.setMouseModeEnabled(this, !DisplayPrefs.isMouseModeEnabled(this))
        refreshFromPrefs()
    }

    internal fun statusText(): String {
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT < 30) {
            sb.append(getString(R.string.cursor_requires_android_11))
        } else {
            val display = if (externalDisplayId != Display.INVALID_DISPLAY && ::displayManager.isInitialized) {
                displayManager.getDisplay(externalDisplayId)
            } else {
                null
            }
            if (display == null) {
                sb.append(getString(R.string.cursor_status_no_display))
            } else {
                val b = externalBounds()
                sb.append(
                    getString(
                        R.string.cursor_status_display,
                        display.displayId,
                        display.name,
                        b?.width() ?: 0,
                        b?.height() ?: 0,
                    ),
                )
                if (externalCursor == null) {
                    sb.append('\n').append(getString(R.string.cursor_overlay_failed))
                }
            }
        }
        if (isMouseModeActive) sb.append('\n').append(getString(R.string.cursor_mouse_mode_hint))
        return sb.toString()
    }

    internal fun phoneBounds(): Rect {
        try {
            val d = if (::displayManager.isInitialized) displayManager.getDisplay(Display.DEFAULT_DISPLAY) else null
            if (d != null) {
                val r = DisplayGeometry.realBounds(d)
                if (!r.isEmpty) return r
            }
        } catch (t: Throwable) {
            Log.w(TAG, "phoneBounds failed", t)
        }
        val m = resources.displayMetrics
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    // ------------------------------------------------------------------ displays & overlays

    private fun onDisplaysChanged() {
        if (!connected) return
        safe("onDisplaysChanged") {
            rebuildExternal()
            updateCursorOverlays()
            panel?.updateStatus()
        }
    }

    /** (Re)creates the cursor overlay for the current target display, if it changed. */
    private fun rebuildExternal() {
        val target: Display? = if (Build.VERSION.SDK_INT >= 30) DisplayPrefs.resolveTargetDisplay(this) else null
        val newId = target?.displayId ?: Display.INVALID_DISPLAY
        if (newId == externalDisplayId && (externalCursor != null || newId == Display.INVALID_DISPLAY)) {
            externalCursor?.refreshBounds()
        } else {
            externalCursor?.remove()
            externalCursor = null
            externalDisplayId = newId
            if (target != null && Build.VERSION.SDK_INT >= 30) {
                externalCursor = CursorOverlay.forDisplay(this, target)
            }
        }
        // The display holding the cursor vanished: bring the cursor back somewhere valid.
        if (curDisplayId != Display.INVALID_DISPLAY &&
            curDisplayId != Display.DEFAULT_DISPLAY &&
            curDisplayId != externalDisplayId
        ) {
            primaryDown = false
            when {
                externalDisplayId != Display.INVALID_DISPLAY -> centerOn(externalDisplayId)
                isMouseModeActive -> centerOn(Display.DEFAULT_DISPLAY)
                else -> curDisplayId = Display.INVALID_DISPLAY
            }
        }
    }

    private fun externalBounds(): Rect? {
        if (externalDisplayId == Display.INVALID_DISPLAY) return null
        externalCursor?.let { return it.bounds }
        return try {
            displayManager.getDisplay(externalDisplayId)?.let { DisplayGeometry.realBounds(it) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun boundsOf(displayId: Int): Rect? = when (displayId) {
        Display.DEFAULT_DISPLAY -> phoneBounds()
        externalDisplayId -> externalBounds()
        else -> null
    }

    private fun centerOn(displayId: Int) {
        val b = boundsOf(displayId) ?: return
        curDisplayId = displayId
        curX = b.width() / 2f
        curY = b.height() / 2f
    }

    private fun clampCursor() {
        val b = boundsOf(curDisplayId) ?: return
        curX = curX.coerceIn(0f, max(0f, b.width() - 1f))
        curY = curY.coerceIn(0f, max(0f, b.height() - 1f))
    }

    /** Shows the pointer on the display currently holding the cursor; hides the others. */
    private fun updateCursorOverlays() {
        val panelOpen = panel?.isShowing == true
        val active = isMouseModeActive || panelOpen
        val onExternal = active && curDisplayId != Display.INVALID_DISPLAY && curDisplayId == externalDisplayId
        val onPhone = isMouseModeActive && curDisplayId == Display.DEFAULT_DISPLAY

        externalCursor?.let {
            if (onExternal) {
                it.moveTo(curX, curY)
                it.show()
            } else {
                it.hide()
            }
        }

        if (onPhone) {
            var pc = phoneCursor
            if (pc == null) {
                val d = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                if (d != null) {
                    pc = CursorOverlay.forDefaultDisplay(this, windowManager, d)
                    phoneCursor = pc
                }
            }
            pc?.moveTo(curX, curY)
            pc?.show()
        } else {
            phoneCursor?.hide()
        }
    }

    /**
     * Makes sure the touchpad has a valid cursor to act on.
     * Touchpad drives the external display (or the phone when mouse mode left the cursor there).
     */
    private fun ensureTouchpadCursor(): Boolean {
        if (Build.VERSION.SDK_INT < 30) {
            panel?.showMessage(getString(R.string.cursor_requires_android_11))
            return false
        }
        if (isMouseModeActive && curDisplayId == Display.DEFAULT_DISPLAY) return true
        if (externalDisplayId == Display.INVALID_DISPLAY || displayManager.getDisplay(externalDisplayId) == null) {
            rebuildExternal()
            updateCursorOverlays()
            panel?.updateStatus()
            if (externalDisplayId == Display.INVALID_DISPLAY) {
                panel?.showMessage(getString(R.string.cursor_status_no_display))
                return false
            }
        }
        if (curDisplayId != externalDisplayId) {
            centerOn(externalDisplayId)
            updateCursorOverlays()
        }
        return curDisplayId != Display.INVALID_DISPLAY
    }

    /**
     * Moves the virtual cursor. With [allowCross], the external display is "to the right" of
     * the phone: leaving the phone's right edge enters the external display's left edge and back.
     */
    private fun moveCursorBy(dx: Float, dy: Float, allowCross: Boolean) {
        val phone = phoneBounds()
        val ext = externalBounds()
        if (curDisplayId == Display.INVALID_DISPLAY ||
            (curDisplayId != Display.DEFAULT_DISPLAY && (curDisplayId != externalDisplayId || ext == null))
        ) {
            if (ext != null) centerOn(externalDisplayId) else centerOn(Display.DEFAULT_DISPLAY)
        }
        var nx = curX + dx
        var ny = curY + dy
        if (curDisplayId == Display.DEFAULT_DISPLAY) {
            val pw = phone.width().toFloat()
            val ph = phone.height().toFloat()
            if (allowCross && ext != null && Build.VERSION.SDK_INT >= 30 && nx >= pw) {
                val ratio = if (ph > 0f) (ny / ph).coerceIn(0f, 1f) else 0.5f
                curDisplayId = externalDisplayId
                nx = (nx - pw).coerceIn(0f, max(0f, ext.width() - 1f))
                ny = ratio * max(0f, ext.height() - 1f)
            } else {
                nx = nx.coerceIn(0f, max(0f, pw - 1f))
                ny = ny.coerceIn(0f, max(0f, ph - 1f))
            }
        } else if (ext != null) {
            val ew = ext.width().toFloat()
            val eh = ext.height().toFloat()
            if (allowCross && nx < 0f) {
                val ratio = if (eh > 0f) (ny / eh).coerceIn(0f, 1f) else 0.5f
                curDisplayId = Display.DEFAULT_DISPLAY
                nx = (phone.width() - 1f + nx).coerceIn(0f, max(0f, phone.width() - 1f))
                ny = ratio * max(0f, phone.height() - 1f)
            } else {
                nx = nx.coerceIn(0f, max(0f, ew - 1f))
                ny = ny.coerceIn(0f, max(0f, eh - 1f))
            }
        }
        curX = nx
        curY = ny
        updateCursorOverlays()
    }

    // ------------------------------------------------------------------ gestures

    private fun pointPath(x: Float, y: Float): Path = Path().apply { moveTo(max(0f, x), max(0f, y)) }

    private fun tap(displayId: Int, x: Float, y: Float, durationMs: Long, label: String) {
        val stroke = GestureDescription.StrokeDescription(pointPath(x, y), 0L, durationMs)
        dispatchOn(displayId, listOf(stroke), label)
    }

    private fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, label: String) {
        val path = Path().apply {
            moveTo(max(0f, x1), max(0f, y1))
            lineTo(max(0f, x2), max(0f, y2))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchOn(displayId, listOf(stroke), label)
    }

    /** Swipe of (dx, dy) centred on the cursor, kept inside the display. */
    private fun scrollAt(displayId: Int, dx: Float, dy: Float) {
        val b = boundsOf(displayId) ?: run {
            Log.w(TAG, "scroll: display $displayId not available")
            return
        }
        val maxX = max(1f, b.width() - 2f)
        val maxY = max(1f, b.height() - 2f)
        val sx = (curX - dx / 2f).coerceIn(1f, maxX)
        val sy = (curY - dy / 2f).coerceIn(1f, maxY)
        val ex = (curX + dx / 2f).coerceIn(1f, maxX)
        val ey = (curY + dy / 2f).coerceIn(1f, maxY)
        if (abs(ex - sx) < 1f && abs(ey - sy) < 1f) return
        swipe(displayId, sx, sy, ex, ey, SCROLL_MS, "scroll")
    }

    private fun isDisplayPresent(displayId: Int): Boolean =
        displayId != Display.INVALID_DISPLAY &&
            ::displayManager.isInitialized &&
            displayManager.getDisplay(displayId) != null

    private fun dispatchOn(
        displayId: Int,
        strokes: List<GestureDescription.StrokeDescription>,
        label: String,
    ): Boolean {
        if (!connected) return false
        if (displayId != Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT < 30) {
            Log.w(TAG, "$label: gestures on display $displayId need Android 11")
            panel?.showMessage(getString(R.string.cursor_requires_android_11))
            return false
        }
        if (!isDisplayPresent(displayId)) {
            Log.w(TAG, "$label: display $displayId is not present")
            panel?.showMessage(getString(R.string.cursor_status_no_display))
            return false
        }
        return try {
            val builder = GestureDescription.Builder()
            if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
            for (s in strokes) builder.addStroke(s)
            val ok = dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "$label cancelled on display $displayId")
                    }
                },
                handler,
            )
            if (!ok) {
                Log.w(TAG, "$label rejected on display $displayId")
                panel?.showMessage(getString(R.string.cursor_gesture_failed))
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "$label failed on display $displayId", t)
            false
        }
    }

    // ------------------------------------------------------------------ physical mouse mode (API 34+)

    private fun applyMouseMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 34) {
            isMouseModeActive = false
            return
        }
        setMouseCapture(enabled)
    }

    @RequiresApi(34)
    private fun setMouseCapture(enabled: Boolean) {
        try {
            val info = serviceInfo ?: return
            val wanted = if (enabled) InputDevice.SOURCE_MOUSE else 0
            if (info.motionEventSources != wanted) {
                info.motionEventSources = wanted
                serviceInfo = info
            }
            isMouseModeActive = enabled
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to ${if (enabled) "enable" else "disable"} mouse capture", t)
            isMouseModeActive = false
        }
        if (!isMouseModeActive) {
            primaryDown = false
            lastEventX = Float.NaN
            lastEventY = Float.NaN
        }
    }

    override fun onMotionEvent(event: MotionEvent) {
        try {
            if (connected && isMouseModeActive) handleMouseEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "onMotionEvent failed", t)
        }
    }

    private fun handleMouseEvent(event: MotionEvent) {
        if (curDisplayId == Display.INVALID_DISPLAY) {
            centerOn(Display.DEFAULT_DISPLAY)
            updateCursorOverlays()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_MOVE,
            -> applyMouseMotion(event)

            MotionEvent.ACTION_DOWN -> {
                applyMouseMotion(event)
                if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) onButtonPress(MotionEvent.BUTTON_PRIMARY)
            }

            MotionEvent.ACTION_UP -> {
                applyMouseMotion(event)
                if (primaryDown) onButtonRelease(MotionEvent.BUTTON_PRIMARY)
            }

            MotionEvent.ACTION_BUTTON_PRESS -> onButtonPress(event.actionButton)

            MotionEvent.ACTION_BUTTON_RELEASE -> onButtonRelease(event.actionButton)

            MotionEvent.ACTION_SCROLL -> onWheel(event)

            MotionEvent.ACTION_CANCEL -> primaryDown = false
        }
    }

    private fun applyMouseMotion(event: MotionEvent) {
        val rx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val ry = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        val dx: Float
        val dy: Float
        if (rx != 0f || ry != 0f) {
            dx = rx
            dy = ry
        } else if (!lastEventX.isNaN() && !lastEventY.isNaN()) {
            dx = event.x - lastEventX
            dy = event.y - lastEventY
        } else {
            dx = 0f
            dy = 0f
        }
        lastEventX = event.x
        lastEventY = event.y
        if (dx != 0f || dy != 0f) moveCursorBy(dx, dy, allowCross = true)
    }

    private fun onButtonPress(button: Int) {
        when (button) {
            MotionEvent.BUTTON_PRIMARY -> if (!primaryDown) {
                primaryDown = true
                pressDisplayId = curDisplayId
                pressX = curX
                pressY = curY
            }
            MotionEvent.BUTTON_SECONDARY -> goBack()
        }
    }

    private fun onButtonRelease(button: Int) {
        if (button != MotionEvent.BUTTON_PRIMARY || !primaryDown) return
        primaryDown = false
        val displayId = pressDisplayId
        if (displayId == Display.INVALID_DISPLAY) return
        val dist = hypot(curX - pressX, curY - pressY)
        if (displayId == curDisplayId && dist > DRAG_THRESHOLD_PX) {
            val duration = (dist / 1.5f).toLong().coerceIn(150L, 1500L)
            swipe(displayId, pressX, pressY, curX, curY, duration, "drag")
        } else {
            tap(displayId, pressX, pressY, TAP_MS, "mouse-click")
        }
    }

    private fun onWheel(event: MotionEvent) {
        pendingScrollV += event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        pendingScrollH += event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val elapsed = SystemClock.uptimeMillis() - lastScrollAt
        if (elapsed >= SCROLL_MS + 20L) {
            flushWheelScroll()
        } else if (!scrollFlushPosted) {
            scrollFlushPosted = true
            handler.postDelayed(flushScrollRunnable, SCROLL_MS + 20L - elapsed)
        }
    }

    private fun flushWheelScroll() {
        if (!connected) return
        val v = pendingScrollV
        val h = pendingScrollH
        pendingScrollV = 0f
        pendingScrollH = 0f
        if (v == 0f && h == 0f) return
        lastScrollAt = SystemClock.uptimeMillis()
        // Wheel up (v > 0) shows content above => finger swipes down. Wheel right (h > 0) => finger swipes left.
        val dy = (v * SCROLL_PX).coerceIn(-2f * SCROLL_PX, 2f * SCROLL_PX)
        val dx = (-h * SCROLL_PX).coerceIn(-2f * SCROLL_PX, 2f * SCROLL_PX)
        scrollAt(curDisplayId, dx, dy)
    }

    // ------------------------------------------------------------------ helpers

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }
}
