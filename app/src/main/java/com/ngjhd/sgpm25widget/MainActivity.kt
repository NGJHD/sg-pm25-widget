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
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.UUID
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

    private lateinit var refreshButton: Button
    private lateinit var refreshSpinner: View

    /**
     * The fetch started by a tap on "Refresh now", until it finishes. Tracked
     * by id rather than by watching for RUNNING: a fetch takes ~0.1 s, and
     * LiveData can skip straight to the finished state.
     */
    private var tappedId: UUID? = null
    private var tappedAt = 0L
    private var wasRunning = false

    /** Tracks the fetch work so the button can show "Refreshing…". */
    private val workObserver = Observer<List<WorkInfo>> { infos ->
        val running = infos.any {
            it.state == WorkInfo.State.RUNNING ||
                (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0)
        }
        var succeeded = false
        var failed = false
        val mine = tappedId?.let { id -> infos.firstOrNull { it.id == id } }
        if (mine != null && mine.state.isFinished) {
            tappedId = null
            succeeded = mine.state == WorkInfo.State.SUCCEEDED
            failed = mine.state == WorkInfo.State.FAILED
        } else if (tappedId != null && System.currentTimeMillis() - tappedAt > 70_000L) {
            tappedId = null // never reported back; do not spin forever
        } else if (tappedId == null && wasRunning && !running) {
            // A fetch this screen did not start (opening the app, the widget).
            succeeded = infos.any { it.state == WorkInfo.State.SUCCEEDED }
            failed = infos.any { it.state == WorkInfo.State.FAILED }
        }
        if (failed) {
            Store(this).loadError()?.let {
                Toast.makeText(this, "Refresh failed: ${it.first}", Toast.LENGTH_LONG).show()
            }
        }
        wasRunning = running
        renderRefreshing(running || tappedId != null, justSucceeded = succeeded)
        renderReading()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** When "Refreshing…" went up; it stays at least [MIN_REFRESHING_MS]. */
    private var refreshingSince = 0L
    private var pendingButton: Runnable? = null

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
        refreshButton = findViewById(R.id.btn_refresh)
        refreshSpinner = findViewById(R.id.refresh_spinner)
        refreshButton.setOnClickListener {
            if (refreshingSince != 0L) return@setOnClickListener // already refreshing
            tappedAt = System.currentTimeMillis()
            renderRefreshing(true)
            WidgetViews.showRefreshing(this)
            Scheduling.armRefreshWatchdog(this)
            tappedId = Scheduling.fetchNow(this, userInitiated = true)
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
        setUpTextScale()
    }

    /** Slider 80-200 % in steps of 10; every change re-pushes the widgets. */
    private fun setUpTextScale() {
        val bar = findViewById<SeekBar>(R.id.text_scale)
        val label = findViewById<TextView>(R.id.text_scale_value)
        val store = Store(this)
        val steps = (Store.MAX_TEXT_SCALE - Store.MIN_TEXT_SCALE) / Store.TEXT_SCALE_STEP
        bar.max = steps
        bar.progress = (store.textScalePercent - Store.MIN_TEXT_SCALE) / Store.TEXT_SCALE_STEP
        label.text = "${store.textScalePercent}%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pct = Store.MIN_TEXT_SCALE + progress * Store.TEXT_SCALE_STEP
                label.text = "$pct%"
                store.textScalePercent = pct // prefs listener re-renders the preview
                WidgetViews.updateAll(this@MainActivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    /**
     * The real widget's RemoteViews, applied inside the app, at the placed
     * widget's size (portrait: min width x max height, in dp), else 76 dp.
     */
    private fun renderPreview() {
        val frame = findViewById<FrameLayout>(R.id.widget_preview)
        val (wDp, hDp) = WidgetViews.widgetIds(this).firstOrNull()?.let { id ->
            val o = AppWidgetManager.getInstance(this).getAppWidgetOptions(id)
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) to
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        }?.takeIf { it.first > 0 && it.second > 0 } ?: (76 to 76)
        frame.layoutParams = frame.layoutParams.apply {
            width = dp(wDp)
            height = dp(hDp)
        }
        frame.removeAllViews()
        try {
            val preview = WidgetViews.buildPreview(this).apply(this, frame)
            disableClicks(preview) // its refresh button would really refresh
            frame.addView(preview)
        } catch (e: Exception) {
            android.util.Log.w("PM_WIDGET", "preview failed: $e")
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("pm25", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(Scheduling.FETCH_WORK)
            .observeForever(workObserver)
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
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(Scheduling.FETCH_WORK)
            .removeObserver(workObserver)
    }

    /**
     * A refresh usually takes well under a second (a recent fix is reused),
     * which on its own changed nothing visible and read as "unresponsive"
     * (user, 10-13). So "Refreshing…" stays up for at least
     * [MIN_REFRESHING_MS], and a success then shows "Updated ✓" briefly.
     */
    private fun renderRefreshing(on: Boolean, justSucceeded: Boolean = false) {
        pendingButton?.let { handler.removeCallbacks(it) }
        pendingButton = null
        if (on) {
            if (refreshingSince == 0L) refreshingSince = System.currentTimeMillis()
            setButton("Refreshing…", enabled = false, spinner = true)
            return
        }
        val wait = refreshingSince + MIN_REFRESHING_MS - System.currentTimeMillis()
        refreshingSince = 0L
        val finish = Runnable {
            if (justSucceeded) {
                setButton("Updated ✓", enabled = true, spinner = false)
                pendingButton = Runnable { setButton("Refresh now", enabled = true, spinner = false) }
                    .also { handler.postDelayed(it, UPDATED_MS) }
            } else {
                setButton("Refresh now", enabled = true, spinner = false)
            }
        }
        if (wait > 0) {
            pendingButton = finish
            handler.postDelayed(finish, wait)
        } else {
            finish.run()
        }
    }

    /**
     * The button is never actually disabled: the disabled background is a
     * pale grey that the white text is unreadable on. Taps while refreshing
     * are ignored in the click listener instead.
     */
    private fun setButton(text: String, enabled: Boolean, spinner: Boolean) {
        refreshButton.alpha = if (enabled) 1f else 0.85f
        refreshButton.text = text
        refreshSpinner.visibility = if (spinner) View.VISIBLE else View.GONE
    }

    private fun renderReading() {
        renderPreview()
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
                Store.Source.LAST_KNOWN -> "your last known location (no new fix within 10 s)"
                Store.Source.DEFAULT -> "no location yet, so Central"
            }
            val hour = runCatching {
                OffsetDateTime.parse(shown.timestamp).format(DateTimeFormatter.ofPattern("h a"))
            }.getOrDefault(shown.timestamp)
            val fetched = DateFormat.getTimeInstance(DateFormat.SHORT).format(shown.fetchedAt)
            val lines = mutableListOf("Reading for $hour (SGT) · checked $fetched", "Region from $sourceText")
            locationLine(shown)?.let { lines += it }
            if (shown.tookMs >= 0) {
                lines += "Last refresh took %.1f s (location %.1f s)".format(shown.tookMs / 1000.0, shown.locMs / 1000.0)
            }
            Store(this).loadError()?.let { (msg, at) ->
                lines += "Last attempt failed at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(at)}: $msg"
            }
            meta.text = lines.joinToString("\n")
        }

        findViewById<RegionMapView>(R.id.regions).set(shown?.all.orEmpty())
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
            !Locator.hasPrecise(this) -> {
                locationStatus.text = "Approximate only. Android blurs it, which can show " +
                    "the wrong region."
                locationButton.text = "Use precise location"
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

    /**
     * Where the location was and how far the nearest region label points
     * are, so a surprising region can be checked against a map.
     */
    private fun locationLine(shown: Store.Shown): String? {
        if (shown.source == Store.Source.DEFAULT) return null
        val (lat, lon) = Store(this).loadLocation() ?: return null
        val acc = if (shown.accuracyM > 0) " ±${shown.accuracyM} m" else ""
        // Age of the fix when it was used. Android may answer a "current
        // location" request with a fix it took moments earlier.
        val fixAt = Store(this).locationTime()
        val age = if (fixAt > 0) {
            val sec = ((shown.fetchedAt - fixAt) / 1000).coerceAtLeast(0)
            if (sec < 120) ", fix ${sec} s old" else ", fix ${sec / 60} min old"
        } else ""
        val dist = Pm25.DEFAULT_REGIONS
            .map { it.name to Pm25.distanceKm(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(2)
            .joinToString(", ") { "${Pm25.displayName(it.first)} %.1f km".format(it.second) }
        return "Location %.4f, %.4f$acc$age\n$dist".format(lat, lon)
    }

    private fun disableClicks(v: View) {
        v.setOnClickListener(null)
        v.isClickable = false
        v.isFocusable = false
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) disableClicks(v.getChildAt(i))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_REFRESHING_MS = 800L
        private const val UPDATED_MS = 1500L
    }
}
