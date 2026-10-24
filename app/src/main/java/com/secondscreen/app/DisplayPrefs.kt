package com.secondscreen.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Shared settings: which display is the "output" (target) display.
 * AUTO = first presentation display that is not the phone, else any non-default display.
 */
object DisplayPrefs {
    const val AUTO = -1

    private const val PREFS = "secondscreen_prefs"
    private const val KEY_DISPLAY = "preferred_display_id"
    private const val KEY_MOUSE_MODE = "mouse_mode_enabled"
    private const val KEY_BUBBLE = "bubble_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPreferredDisplayId(ctx: Context): Int = prefs(ctx).getInt(KEY_DISPLAY, AUTO)

    fun setPreferredDisplayId(ctx: Context, id: Int) {
        prefs(ctx).edit().putInt(KEY_DISPLAY, id).apply()
    }

    fun isMouseModeEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MOUSE_MODE, false)

    fun setMouseModeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MOUSE_MODE, enabled).apply()
    }

    fun isBubbleEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BUBBLE, true)

    fun setBubbleEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE, enabled).apply()
    }

    /** All displays other than the phone's own screen. */
    fun externalDisplays(ctx: Context): List<Display> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** The display apps/cursor should target: the user's choice if connected, otherwise AUTO. */
    fun resolveTargetDisplay(ctx: Context): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val preferred = getPreferredDisplayId(ctx)
        if (preferred != AUTO) {
            dm.getDisplay(preferred)?.let { if (it.displayId != Display.DEFAULT_DISPLAY) return it }
        }
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?.let { return it }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
