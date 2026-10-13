package com.ngjhd.sgpm25widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Builds and pushes the RemoteViews for every widget (they all show the same). */
object WidgetViews {

    const val ACTION_REFRESH = "com.ngjhd.sgpm25widget.ACTION_REFRESH"
    const val ACTION_CANCEL_REFRESH = "com.ngjhd.sgpm25widget.ACTION_CANCEL_REFRESH"
    const val ACTION_REFRESH_TIMEOUT = "com.ngjhd.sgpm25widget.ACTION_REFRESH_TIMEOUT"
    const val ACTION_ALARM_TICK = "com.ngjhd.sgpm25widget.ACTION_ALARM_TICK"

    /** Background before the first reading, and after a failure with none. */
    private const val NO_DATA_COLOR = 0xFF5E6878.toInt()

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, Pm25WidgetProvider::class.java))

    fun hasWidgets(context: Context) = widgetIds(context).isNotEmpty()

    /**
     * Pushes the stored reading to every widget, with the refresh overlay down.
     * [failed] = the last attempt failed and no retry is coming; only visible
     * when there is no reading at all (an old value simply stays).
     */
    fun updateAll(context: Context, failed: Boolean = false) {
        push(context, build(context, refreshing = false, failed = failed))
    }

    /** Dim + spinner, and the refresh icon becomes a cancel X. */
    fun showRefreshing(context: Context) {
        push(context, build(context, refreshing = true, failed = false))
    }

    private fun push(context: Context, views: RemoteViews) {
        val ids = widgetIds(context)
        if (ids.isNotEmpty()) AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    /** Text sizes (sp) at 100%, matching widget_pm25.xml. */
    private const val LABEL_SP = 9f
    private const val REGION_SP = 12f
    private const val VALUE_SP = 24f
    private const val SMALL_TEXT_MAX_SCALE = 1.2f

    /** The widget exactly as it is pushed, for the in-app preview. */
    fun buildPreview(context: Context): RemoteViews = build(context, refreshing = false, failed = false)

    private fun build(context: Context, refreshing: Boolean, failed: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pm25)
        val store = Store(context)
        val shown = store.loadShown()

        // Text size setting (user, 10-13). The number takes the full scale;
        // the two small lines grow at most [SMALL_TEXT_MAX_SCALE], or a 1x1
        // tile runs out of height (measured: at 160 % across the board the
        // number was cut off; at 200 % it vanished).
        val k = store.textScalePercent / 100f
        val kSmall = k.coerceAtMost(SMALL_TEXT_MAX_SCALE)
        val sp = android.util.TypedValue.COMPLEX_UNIT_SP
        views.setTextViewTextSize(R.id.widget_label, sp, LABEL_SP * kSmall)
        views.setTextViewTextSize(R.id.widget_region, sp, REGION_SP * kSmall)
        views.setTextViewTextSize(R.id.widget_value, sp, VALUE_SP * k)

        if (shown != null) {
            val band = Pm25.band(shown.value)
            views.setInt(R.id.widget_bg, "setColorFilter", band.color)
            views.setTextViewText(R.id.widget_region, Pm25.displayName(shown.region))
            views.setTextViewText(R.id.widget_value, shown.value.toString())
            views.setTextColor(R.id.widget_label, band.text)
            views.setTextColor(R.id.widget_region, band.text)
            views.setTextColor(R.id.widget_value, band.text)
            views.setViewVisibility(R.id.widget_value, View.VISIBLE)
            views.setContentDescription(
                R.id.widget_root,
                "${Pm25.displayName(shown.region)} PM2.5 ${shown.value}, ${band.label}",
            )
        } else {
            views.setInt(R.id.widget_bg, "setColorFilter", NO_DATA_COLOR)
            views.setTextViewText(R.id.widget_region, if (failed) "Failed" else "–")
            views.setTextViewText(R.id.widget_value, if (failed) "Tap ⟳" else "…")
            views.setTextColor(R.id.widget_label, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_region, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_value, 0xFFFFFFFF.toInt())
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // Tap the widget -> the app screen (all regions, setup status).
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            ),
        )

        // Top-right icon: refresh, or cancel while a fetch is in flight. One
        // ImageView with its image + PendingIntent swapped - the widget is 1x1,
        // and One UI refuses to render a widget layout containing a bare <View>.
        val iconIntent = Intent(context, Pm25WidgetProvider::class.java)
            .setAction(if (refreshing) ACTION_CANCEL_REFRESH else ACTION_REFRESH)
        views.setImageViewResource(
            R.id.widget_refresh,
            if (refreshing) R.drawable.ic_close else R.drawable.ic_refresh,
        )
        views.setContentDescription(
            R.id.widget_refresh,
            if (refreshing) "Cancel the refresh" else "Refresh now",
        )
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(context, if (refreshing) 11 else 10, iconIntent, flags),
        )

        val overlay = if (refreshing) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_dim, overlay)
        views.setViewVisibility(R.id.widget_spinner, overlay)
        return views
    }
}
