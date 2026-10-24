package com.secondscreen.app.cursor

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.secondscreen.app.R

/** Touchpad + buttons panel shown at the bottom of the phone screen. */
internal class TouchpadPanel(
    private val service: CursorAccessibilityService,
    private val windowManager: WindowManager,
) {
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    var isShowing: Boolean = false
        private set

    private val statusView: TextView = TextView(service).apply {
        setTextColor(0xFFE0E0E0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
    }

    private lateinit var mouseButton: TextView

    private val touchpad = TouchpadView(service, object : TouchpadView.Listener {
        override fun onMove(dx: Float, dy: Float) = service.touchpadMove(dx, dy)
        override fun onTap() = service.clickAtCursor()
        override fun onDoubleTap() = service.doubleClickAtCursor()
        override fun onLongPress() = service.longPressAtCursor()
        override fun onScroll(dirX: Float, dirY: Float) = service.touchpadScroll(dirX, dirY)
    })

    private val root: LinearLayout = buildRoot()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        title = "SecondScreenTouchpad"
    }

    private fun buildRoot(): LinearLayout {
        val radius = 20f * density
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                setColor(0xF0202634.toInt())
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        layout.addView(
            statusView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val padFrame = FrameLayout(service)
        val hint = TextView(service).apply {
            text = service.getString(R.string.cursor_touchpad_hint)
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        padFrame.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        padFrame.addView(
            touchpad,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        layout.addView(
            padFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(8f)
            },
        )

        val row1 = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeButton(R.string.cursor_btn_click) { service.clickAtCursor() }, buttonParams())
        row1.addView(makeButton(R.string.cursor_btn_long_click) { service.longPressAtCursor() }, buttonParams())
        row1.addView(makeButton(R.string.cursor_btn_back) { service.goBack() }, buttonParams())

        val row2 = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeButton(R.string.cursor_btn_home) { service.goHome() }, buttonParams())
        mouseButton = makeButton(R.string.cursor_btn_mouse_off) { service.toggleMouseModeFromPanel() }
        row2.addView(mouseButton, buttonParams())
        row2.addView(
            makeButton(R.string.cursor_btn_close, 0xFF5C6370.toInt()) { service.closeTouchpad() },
            buttonParams(),
        )

        val rowParams = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        layout.addView(row1, rowParams())
        layout.addView(row2, rowParams())
        return layout
    }

    private fun buttonParams() =
        LinearLayout.LayoutParams(0, dp(44f), 1f).apply {
            setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        }

    private fun makeButton(textRes: Int, color: Int = 0xFF3F51B5.toInt(), onClick: () -> Unit): TextView =
        TextView(service).apply {
            text = service.getString(textRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(color)
            }
            setOnClickListener {
                try {
                    onClick()
                } catch (t: Throwable) {
                    Log.e(TAG, "Touchpad button action failed", t)
                }
            }
        }

    fun show() {
        if (isShowing) return
        try {
            params.height = (service.phoneBounds().height() * 0.6f).toInt()
            updateStatus()
            windowManager.addView(root, params)
            isShowing = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to show touchpad panel", t)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        try {
            windowManager.removeViewImmediate(root)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to remove touchpad panel", t)
        }
    }

    /** Re-applies the panel height (e.g. after rotation). */
    fun relayout() {
        if (!isShowing) return
        try {
            params.height = (service.phoneBounds().height() * 0.6f).toInt()
            windowManager.updateViewLayout(root, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to relayout touchpad panel", t)
        }
    }

    fun updateStatus() {
        statusView.text = service.statusText()
        mouseButton.text = service.getString(
            if (service.isMouseModeActive) R.string.cursor_btn_mouse_on else R.string.cursor_btn_mouse_off,
        )
    }

    /** Shows a transient message in the status line (until the next status refresh). */
    fun showMessage(message: String) {
        statusView.text = message
    }

    private companion object {
        const val TAG = "SecondScreen"
    }
}
