# SG PM2.5 widget

A 1x1 Android home-screen widget that shows Singapore's **1-hour PM2.5**
reading (µg/m³) for the region nearest to you: North, South, East, West or
Central. The background colour is the reading's NEA band.

```
 ┌─────────⟳┐
 │  North   │
 │    73    │
 └──────────┘
```

| Band | 1-hour PM2.5 (µg/m³) | Colour |
|---|---|---|
| Normal | 0–55 | green |
| Elevated | 56–150 | yellow |
| High | 151–250 | orange |
| Very high | 251+ | red |

Data: [data.gov.sg real-time PM2.5 API](https://api-open.data.gov.sg/v2/real-time/api/pm25).
This is the 1-hour PM2.5 concentration, **not** the PSI (a different index on a
different endpoint).

## How it behaves

- **Region**: the region whose label point is nearest to the phone's location.
  It uses a fresh location if it can, then the last known one, then **Central**
  if there has never been one.
- **Updates**: every hour at **X:20** (the feed publishes each hourly reading
  about 15 minutes after the hour), **only while the screen is on**. If the
  phone was asleep, it updates as soon as it wakes. If the X:20 fetch still
  gets last hour's reading, it tries again 10 minutes later.
- **Refresh button** (top right): gets a new location and reading right away.
  While it works, the widget dims, shows a spinner, and the button becomes an
  **X** that cancels.
- **Tap the widget** to open the app: all five regions, when the reading is
  from, and the location setup.

## Setup on the phone

Placing the widget opens the app screen. For hourly updates to work:

1. **Location → Allow all the time** (the app insists). With "While using
   the app" only, the hourly updates use the location from the last time the
   app was open.
2. **Battery → Unrestricted** (the app insists). Otherwise Android parks the
   hourly alarm and the widget never updates.
3. If **Data Saver** is on, allow the app to use data while it is on (the app
   insists on this too).

## Build

Needs JDK 17+ and the Android SDK (`local.properties` with `sdk.dir`).

```
gradlew.bat :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
gradlew.bat :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
gradlew.bat :app:testDebugUnitTest  # parsing, nearest region, bands, X:20 maths
```

A release build is signed only if `keystore.properties` (git-ignored) exists:

```
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Sideload with `adb install -r app-release.apk`. A debug build and a release
build are signed with different keys, so one will not install over the other;
switching means uninstalling first, which removes the placed widgets.
