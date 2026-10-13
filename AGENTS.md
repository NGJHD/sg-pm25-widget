# AGENTS.md — SG PM2.5 Widget (Android)

Working notes for this repo: requirements, architecture, constraints. Keep it
describing the app **as it is now**. User-facing behaviour is in `README.md`.

## Requirements (from the user, 2026-10-13)

1. **1x1 home-screen widget** showing the **1-hour PM2.5** value
   (`pm25_one_hourly`) from `https://api-open.data.gov.sg/v2/real-time/api/pm25`.
   Not the PSI: the user chose PM2.5 1-hr explicitly.
2. **Region = nearest to the device**, among the five label locations the
   feed publishes (`regionMetadata`). NEA publishes no boundaries; the
   North/Central split falls at latitude ~1.388. Location order: OS fix
   < 10 min old → new fix from **all enabled providers at once** (10 s) →
   OS last known → the app's own saved fix → **Central**. Precise location
   is requested (approximate is ~3 km).
3. **Display**: three centred lines, "1-hr PM2.5" (user, 10-13) over the
   region name ("North") over the value ("73"). Background colour = NEA 1-hr PM2.5 band: 0–55 Normal (green),
   56–150 Elevated (yellow, dark text), 151–250 High (orange), 251+ Very high
   (red). `Pm25.Band`.
4. **Refresh button** top-right on a translucent circle: re-locates and
   re-fetches immediately. While a fetch is in flight: dim + spinner, and the
   icon becomes an **X that cancels** (same ImageView, swapped image and
   PendingIntent). The spinner can never run forever: a failed attempt takes
   it down, retries stop after 4 attempts, and a 60 s watchdog clears it.
5. **Schedule: every hour at X:20** (the user's choice; the feed publishes
   ~X:15 — the 11:00 reading had `updatedTimestamp` 11:15:51), **only with
   the screen on**. Asleep → update at the next wake. If X:20 still returns
   the previous hour, retry in 10 min.
6. **Sideloaded only, never on Play** — so `ACCESS_BACKGROUND_LOCATION` is
   fine. App name **"SG PM2.5 Widget"** (user, 10-13), package
   **`com.ngjhd.sgpm25widget`** (permanent, and registered for Android
   developer verification). Icon: `art/Haze.png` (user's), layers built by
   `art/make_icons.py`.
8. **"Refresh now" in the app shows its state**: "Refreshing…" with a
   spinner for at least 0.8 s, then "Updated ✓" for 1.5 s; a failure shows a
   toast with the reason. A refresh is often ~0.1 s, which on its own looked
   unresponsive. The app screen also shows the location used, the distances
   to the nearest label points, the time taken, and the last error.
7. **Setup gate** (carried over from Webglance): battery **Unrestricted**,
   the **Data Saver** exemption and **location "Allow all the time"** (user,
   10-13) are all mandatory; an unskippable dialog (only button opens the
   setting, Back closes the screen) on every resume, plus a banner. Order:
   battery, Data Saver, location, then background location. Location takes
   two steps because Android 11+ refuses a background request until
   foreground access is granted.

## Architecture

- Kotlin, classic Views, no AppCompat. Only dependency: WorkManager 2.10.0.
- **One global state, not per widget**: every widget shows the same thing,
  one fetch pushes all of them (`WidgetViews.updateAll`). `Store` holds the
  last reading and the last location (SharedPreferences `pm25`).
- `Pm25` — parsing, nearest region (equirectangular, lon scaled by
  cos(lat)), bands, blocking HTTP fetch. Pure logic is unit-tested.
- `Locator` — plain `LocationManager` (no Play services).
  `getCurrentLocation` (API 30+) / `requestSingleUpdate` below on every
  enabled provider at once, first fix wins, 10 s timeout. **Measured 10-13
  (emulator): asking only `fused` waited the full timeout with no fix** and
  fell back to Central — the cause of "slow refresh + wrong region".
- `FetchWorker` — WorkManager one-shot: download and locate **in parallel**,
  then save and push. A tap gets one attempt (expedited on Android 12+);
  scheduled runs get 4 with 30 s exponential backoff and are skipped with
  the screen off.
- `Scheduling` — AlarmManager chain: `setWindow(RTC, next X:20, 60 s)`,
  **inexact and non-wakeup**; each tick arms the next. Plus an hourly
  WorkManager periodic **backstop** that only acts when the value predates
  the most recent X:20.
- `ScreenOnReceiver` (runtime-registered in `App`) — catch-up on wake with
  `ExistingWorkPolicy.KEEP`. `BootReceiver` — re-arms after boot/update.
- `MainActivity` — app screen **and** the widget's configure activity. Sets
  `RESULT_OK` immediately (the widget is always kept) and starts the schedule,
  because the launcher sends no `onUpdate` for a placement with a configure
  activity. Opening it fetches (foreground ⇒ fresh fix with either grant).
- `PowerSetup` — the setup gate. `SystemBars.pad` — insets for every activity.

## Constraints (measured in Webglance, the app this was forked from)

- **No bare `<View>` in the widget layout** — One UI then refuses to render
  the widget ("Couldn't add widget"). Use `FrameLayout`.
- **Inexact alarms only**: no exact-alarm permission. With Unrestricted and
  the screen on, ticks landed 5–25 s after due. Without Unrestricted they were
  parked ~a year out.
- Non-wakeup alarms due during sleep are held and delivered at the next wake
  — that *is* the catch-up mechanism. Screen-on receiver closes the gap.
- WorkManager does **not** interrupt a plain `Worker`: every wait polls
  `isStopped`.
- Data Saver without the exemption → netd refuses DNS for the app in the
  background.
- `android:fitsSystemWindows` does not work under targetSdk 36 edge-to-edge,
  and it replaces padding. Use `SystemBars.pad`.
- `onUpdate` re-arms the schedule as a self-heal — only safe while
  `widget_info.xml` has **no `updatePeriodMillis`**.
- Implicit broadcasts to the provider are never delivered; every custom
  action is an explicit-component PendingIntent.
- **Never `am force-stop` the app while widgets are placed** (kills the
  launcher's host binding; `am kill` is safe).

## Build

- Wrapper Gradle 8.14.3, AGP 8.9.3, Kotlin 2.0.20, compileSdk/targetSdk 36,
  minSdk 26. versionCode 2 / versionName 1.1 — bump versionCode for every
  release you install over an older one.
- Release signing: git-ignored `keystore.properties` → a key **separate from
  Webglance's**. Debug and release builds have different signatures; switching
  between them needs an uninstall, which removes placed widgets.
- Release strips `Log.v/d/i/w` (the fetch logs coordinates).
- Log tags: `PM_FETCH`, `PM_SCHED`, `PM_WIDGET`, `PM_LOC`, `PM_POWER`.
