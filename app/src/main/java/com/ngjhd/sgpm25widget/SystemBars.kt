package com.ngjhd.sgpm25widget

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Keeps content clear of the status and navigation bars.
 *
 * WHY THIS EXISTS (measured 09-22): `android:fitsSystemWindows="true"` does
 * NOT inset anything on this app. With targetSdk 36 the app is edge-to-edge by
 * default, and the Save/Cancel row on the configure screen rendered UNDERNEATH
 * the navigation bar - `dumpsys`/uiautomator put the buttons at y 2370..2487
 * with navigationBarBackground at 2412..2520, so only the top ~40 px of a
 * 117 px button was tappable. (An earlier `clipToPadding="false"` on the same
 * ScrollView made it worse by also cancelling the bottom inset.)
 *
 * The same attribute ALSO silently ate the widget list's horizontal padding,
 * because fitsSystemWindows REPLACES a view's padding rather than adding to
 * it - which is why the list rendered at x=0 despite `android:padding="20dp"`.
 *
 * So the insets are applied here, explicitly, ON TOP OF whatever padding the
 * layout already declares.
 */
object SystemBars {

    /**
     * Adds the system-bar insets to [view]'s existing padding, and keeps them
     * applied across configuration changes (fold/unfold, rotation).
     */
    fun pad(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom,
                )
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    baseLeft + insets.systemWindowInsetLeft,
                    baseTop + insets.systemWindowInsetTop,
                    baseRight + insets.systemWindowInsetRight,
                    baseBottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }
}
