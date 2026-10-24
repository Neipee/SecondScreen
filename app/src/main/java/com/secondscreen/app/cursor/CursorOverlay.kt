package com.secondscreen.app.cursor

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

internal object DisplayGeometry {
    /** Full (real) size of a display in its current rotation, in px. */
    fun realBounds(display: Display): Rect {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}

/**
 * A non-touchable pointer drawn in an accessibility overlay on one display.
 * Touches/gestures go straight through it to the app underneath.
 */
internal class CursorOverlay private constructor(
    val displayId: Int,
    private val display: Display,
    private val windowManager: WindowManager,
    context: Context,
) {
    private val view = PointerView(context)

    private val params = WindowManager.LayoutParams(
        view.viewWidth,
        view.viewHeight,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "SecondScreenCursor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    var bounds: Rect = computeBounds()
        private set

    var isShowing: Boolean = false
        private set

    private fun computeBounds(): Rect {
        try {
            val r = DisplayGeometry.realBounds(display)
            if (!r.isEmpty) return r
        } catch (t: Throwable) {
            Log.w(TAG, "getRealMetrics failed for display $displayId", t)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Rect(windowManager.currentWindowMetrics.bounds)
            } catch (t: Throwable) {
                Log.w(TAG, "currentWindowMetrics failed for display $displayId", t)
            }
        }
        return Rect(0, 0, 1920, 1080)
    }

    fun refreshBounds() {
        bounds = computeBounds()
    }

    fun show() {
        if (isShowing) return
        try {
            windowManager.addView(view, params)
            isShowing = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to add cursor overlay on display $displayId", t)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to remove cursor overlay on display $displayId", t)
        }
    }

    fun remove() = hide()

    /** Moves the arrow tip to (x, y) in display coordinates. */
    fun moveTo(x: Float, y: Float) {
        params.x = x.roundToInt() - view.tipOffset
        params.y = y.roundToInt() - view.tipOffset
        if (!isShowing) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to move cursor overlay on display $displayId", t)
            isShowing = false
        }
    }

    companion object {
        private const val TAG = "SecondScreen"

        /** Cursor on the phone screen, using the accessibility service's own WindowManager. */
        fun forDefaultDisplay(context: Context, windowManager: WindowManager, display: Display): CursorOverlay? =
            try {
                CursorOverlay(display.displayId, display, windowManager, context)
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to create phone cursor overlay", t)
                null
            }

        /** Cursor on another display, through a window context bound to that display. */
        @RequiresApi(Build.VERSION_CODES.R)
        fun forDisplay(serviceContext: Context, display: Display): CursorOverlay? =
            try {
                val windowContext = serviceContext
                    .createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                val wm = windowContext.getSystemService(WindowManager::class.java)
                CursorOverlay(display.displayId, display, wm, windowContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to create cursor overlay on display ${display.displayId}", t)
                null
            }
    }
}
