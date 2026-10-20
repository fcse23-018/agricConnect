# 14 — Google Maps Platform (map + weather)

This document covers the two Google integrations and, most importantly, how the
**credentials** are handled. It is the reference for anyone building the Android
app or deploying the backend.

## 1. Why there are two different keys

There are two separate Google credentials and they must never be the same value:

| Where | What it does | How it is supplied | How it is protected |
| --- | --- | --- | --- |
| **Android app** | Renders the map used to draw plot boundaries (Maps SDK for Android) | `mapsApiKey` in the git-ignored `android/local.properties` | Restricted in Google Cloud Console to package name + signing SHA-1 |
| **Backend (server)** | Optional weather source (Google Weather API) | `GOOGLE_WEATHER_API_KEY` env var on Render | Restricted by API + IP. Never leaves the server |

**A key compiled into an APK is extractable.** That is expected and is exactly why
Google lets you restrict an Android key to your package name and signing
certificate: another app cannot use it even if it copies the string. The backend
key is different — it is a bearer credential, so it stays server-side and is never
sent to a device or written into any API response.

## 2. Android: Maps SDK for Android

### Providing the key

Add it to `android/local.properties` (already in `.gitignore`):

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
mapsApiKey=AIza...
```

`android/app/build.gradle.kts` reads it (a `-PmapsApiKey=...` Gradle property wins
if both are set) and injects it twice:

- `BuildConfig.MAPS_API_KEY` — so the UI can tell "no key configured" from "map
  failed to load";
- the `com.google.android.geo.API_KEY` manifest placeholder — which is what the
  SDK itself reads.

There is **no default key**. If `mapsApiKey` is absent the build still succeeds and
`PlotBoundaryMap` renders an explained "Map unavailable" panel instead of a blank
grey rectangle. This matters for CI and for a fresh clone: no feature other than
the map drawing surface depends on the key, and a blank map would look like a bug.

### Where the map is used

`ui/components/PlotBoundaryMap.kt` is the single map component. It is embedded in
the shared `BoundaryEditor`, so **both** Farm Management and Satellite Monitoring
get it at once without a second implementation:

- tap the map to add a corner;
- drag a numbered pin to move a corner;
- the polygon is drawn from the same `GeoVertex` list the manual coordinate fields
  edit, so the map and the text fields are two views of one boundary — not two
  boundaries that can disagree.

The map **does not** compute the stored area. The server measures the boundary from
the real geometry (`backend/src/services/geometry.js`, tangent-plane projection +
shoelace). The figure shown while drawing is the same local estimate the rest of
the app uses and is labelled approximate (`~1.23 ha`).

### Required Cloud Console setup

1. Enable **Maps SDK for Android**.
2. Create an API key, then under *Application restrictions* choose **Android apps**
   and add an entry for:
   - package `bw.agriconnect` with your **release** signing SHA-1, and
   - package `bw.agriconnect.debug` with your **debug** SHA-1 (the debug build uses
     `applicationIdSuffix = ".debug"`).
3. Under *API restrictions*, allow only **Maps SDK for Android** for this key.

Get the debug SHA-1 with:

```
cd android && ./gradlew signingReport
```

### Why osmdroid was removed

`org.osmdroid:osmdroid-android` was declared as a dependency but never used — no
map rendered anywhere, and `WRITE_EXTERNAL_STORAGE` (its tile cache permission) was
requested for nothing. Both were removed when the Google map landed, which drops a
permission the app never needed.

## 3. Backend: Google Weather API (optional)

The agricultural analysis needs a **series** of past days — a 14-day rainfall total,
a 7-day heat maximum, a soil-moisture trend. The default provider is **Open-Meteo**,
which is free, keyless, and returns that archive.

Google Weather is available as an opt-in second source:

```
WEATHER_PROVIDER=GOOGLE
GOOGLE_WEATHER_API_KEY=<server-side key>
```

**It is not a drop-in replacement, and the code does not pretend it is.** Google's
historical endpoint returns **24 hours**, and its daily endpoint returns a
**forecast**. So `src/services/googleWeather.js` supplies current conditions and a
forward forecast, and returns `null` — never zero — for everything it cannot supply
(14-day rainfall, soil moisture). A fabricated `0 mm` would read as "no rain" and
silently corrupt a drought alert.

Daily mean temperature is the midpoint of the published max/min; that derivation is
stated in the response `note` rather than presented as a measured mean.

The API key never appears in a response, and an HTTP failure is reported by status
code only — the request URL carries the key inside a query parameter, so echoing a
response body could echo the credential.

### Recommendation

Keep `WEATHER_PROVIDER=OPEN_METEO` unless you specifically need Google's forecast
hours. If you do switch, consider keeping Open-Meteo for the historical series,
because Google alone cannot drive the dry-spell and heat-stress rules in
`docs/12-SATELLITE-MONITORING.md`.

## 4. Rotating a key that has been exposed

If a key is ever pasted into a chat, a ticket or a commit:

1. **Rotate it** in Google Cloud Console immediately — create a new key.
2. Restrict the new key as described above *before* using it.
3. Update `android/local.properties` (never a tracked file) and/or the
   `GOOGLE_WEATHER_API_KEY` value in the Render dashboard.
4. Confirm it never entered the repository:
   `git grep -n "AIzaSy"` should return nothing.

## 5. What is not used

The key's enabled APIs may also include Places, Solar and Maps JavaScript. This app
currently uses **Maps SDK for Android** and, optionally, the **Weather API**. Places
and Solar are not wired up; enabling them costs nothing while unused, but they
should be removed from the key's API restrictions if you do not plan to add them,
because a narrower key is a smaller blast radius.
