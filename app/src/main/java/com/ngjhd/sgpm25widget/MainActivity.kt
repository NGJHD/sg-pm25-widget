package com.ngjhd.sgpm25widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.ExistingWorkPolicy
import java.text.DateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The app screen, and also the widget's configure activity: placing a widget
 * opens it, so the first run (install -> add widget) still sees the location
 * request and the setup gate.
 *
 * Shows the value the widget shows, all five regions, and the location
 * permission state. Opening it fetches (the app is in the foreground then, so
 * even "While using the app" location gives a fresh fix).
 */
class MainActivity : Activity() {

    private lateinit var warning: TextView
    private lateinit var locationStatus: TextView
    private lateinit var locationButton: Button

    /** Set when opened by placing (or reconfiguring) a widget. */
    private var configuringId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderReading() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.pad(findViewById(R.id.main_root))
        warning = findViewById(R.id.setup_warning)
        locationStatus = findViewById(R.id.location_status)
        locationButton = findViewById(R.id.btn_location)

        warning.setOnClickListener {
            PowerSetup.currentProblem(this)?.let { PowerSetup.openSettingFor(this, it) }
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            WidgetViews.showRefreshing(this)
            Scheduling.armRefreshWatchdog(this)
            Scheduling.fetchNow(this, userInitiated = true)
        }

        configuringId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (configuringId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Keep the widget whatever happens next (Back, or the setup
            // dialog closing the screen): there is nothing to cancel, and
            // without a location it simply shows Central.
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configuringId),
            )
            // With a configure activity the launcher does not send onUpdate
            // for the placement, so start everything here.
            WidgetViews.updateAll(this)
            Scheduling.start(this)
            findViewById<Button>(R.id.btn_done).apply {
                visibility = View.VISIBLE
                setOnClickListener { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("pm25", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        renderReading()
        renderLocation()
        val problem = PowerSetup.currentProblem(this)
        warning.visibility = if (problem != null) View.VISIBLE else View.GONE
        problem?.let { warning.text = PowerSetup.bannerText(this, it) }
        PowerSetup.warnIfRestricted(this)
        // In the foreground, so this gets a fresh fix with either location
        // grant. KEEP: never restart a fetch that is already running.
        Scheduling.fetchNow(this, userInitiated = true, policy = ExistingWorkPolicy.KEEP)
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("pm25", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun renderReading() {
        val shown = Store(this).loadShown()
        val card = findViewById<LinearLayout>(R.id.current_card)
        val region = findViewById<TextView>(R.id.current_region)
        val value = findViewById<TextView>(R.id.current_value)
        val bandText = findViewById<TextView>(R.id.current_band)
        val meta = findViewById<TextView>(R.id.current_meta)

        if (shown == null) {
            (card.background.mutate() as GradientDrawable).setColor(getColor(R.color.text_secondary))
            region.text = "No reading yet"
            value.text = "…"
            bandText.text = ""
            listOf(region, value, bandText).forEach { it.setTextColor(0xFFFFFFFF.toInt()) }
            meta.text = ""
        } else {
            val band = Pm25.band(shown.value)
            (card.background.mutate() as GradientDrawable).setColor(band.color)
            region.text = Pm25.displayName(shown.region)
            value.text = shown.value.toString()
            bandText.text = band.label
            listOf(region, value, bandText).forEach { it.setTextColor(band.text) }
            val sourceText = when (shown.source) {
                Store.Source.CURRENT -> "your current location"
                Store.Source.LAST_KNOWN -> "your last known location"
                Store.Source.DEFAULT -> "no location yet, so Central"
            }
            val hour = runCatching {
                OffsetDateTime.parse(shown.timestamp).format(DateTimeFormatter.ofPattern("h a"))
            }.getOrDefault(shown.timestamp)
            val fetched = DateFormat.getTimeInstance(DateFormat.SHORT).format(shown.fetchedAt)
            meta.text = "Reading for $hour (SGT) · checked $fetched\nRegion from $sourceText"
        }

        val list = findViewById<LinearLayout>(R.id.regions)
        list.removeAllViews()
        val all = shown?.all.orEmpty()
        for (name in listOf("north", "south", "east", "west", "central")) {
            val v = all[name]
            val row = TextView(this).apply {
                textSize = 15f
                setPadding(0, dp(10), 0, dp(10))
                setTextColor(getColor(R.color.text_primary))
                text = if (v == null) "${Pm25.displayName(name)}   –"
                else "${Pm25.displayName(name)}   $v   ${Pm25.band(v).label}"
                if (name == shown?.region) setTypeface(typeface, android.graphics.Typeface.BOLD)
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (v == null) getColor(R.color.stroke) else Pm25.band(v).color)
                    setSize(dp(12), dp(12))
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = dp(12)
            }
            list.addView(row)
        }
    }

    private fun renderLocation() {
        val hasFg = Locator.hasForeground(this)
        val hasBg = Locator.hasBackground(this)
        val on = Locator.isLocationOn(this)
        locationButton.visibility = View.VISIBLE
        when {
            !hasFg -> {
                locationStatus.text = "Not allowed. The widget shows Central until it has your location."
                locationButton.text = "Allow location"
                locationButton.setOnClickListener { PowerSetup.requestLocation(this) }
            }
            !hasBg -> {
                locationStatus.text = "Allowed only while using the app. Hourly updates use the " +
                    "location from the last time you opened the app. Choose \"Allow all the time\" " +
                    "so they follow you."
                locationButton.text = "Allow all the time"
                locationButton.setOnClickListener { PowerSetup.requestLocation(this) }
            }
            !on -> {
                locationStatus.text = "Location is turned off on this phone, so updates use the last known location."
                locationButton.text = "Turn on location"
                locationButton.setOnClickListener {
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
            }
            else -> {
                locationStatus.text = "Allowed all the time. Each update uses your current region."
                locationButton.visibility = View.GONE
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        renderLocation()
        // onResume follows and re-runs the gate, which asks for the next grant.
        if (PowerSetup.onLocationResult(this, requestCode, permissions, results)) {
            Scheduling.fetchNow(this, userInitiated = true)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
