# Satellite Monitoring — Thresholds & Classification Rules

This document explains every threshold used by the monitoring analysis engine
(`backend/src/services/satelliteAnalysis.js`). It is returned by the API at
`GET /api/v1/satellite/overview` under `thresholdDocumentation` so a client can
show exactly which numbers raised an alert.

---

## 1. Observation quality gates

| Threshold | Env var | Default | Meaning |
|-----------|---------|---------|---------|
| **Default look-back window** | `SATELLITE_DEFAULT_DAYS` | `120` | Days of history requested when the caller omits `days`/`daysBack`. |
| **Maximum look-back window** | `SATELLITE_MAX_DAYS` | `365` | Hard cap — a request exceeding this is rejected with `400`. |
| **Refresh interval** | `SATELLITE_REFRESH_HOURS` | `6` | How often the overview is allowed to re-fetch from the crop-api (server-side cache TTL). |
| **Stale observation** | `SATELLITE_STALE_DAYS` | `10` | If the newest observation is older than this, `observationStatus.note` warns and a `STALE_OBSERVATION` alert fires. |
| **Stress baseline** | `SATELLITE_STRESS_NDVI` | `0.30` | NDVI below this raises a `POSSIBLE_STRESS` alert and drives the legacy advice block. |

---

## 2. Drop detection (what counts as a "drop")

A drop is a local minimum in the **smoothed** NDVI or EVI series that is
sufficiently deeper than its surrounding context. The engine does **not** flag
every small wiggle — it looks for a meaningful excursion.

| Threshold | Env var | Default | Meaning |
|-----------|---------|---------|---------|
| **Absolute drop** | `SATELLITE_DROP_ABSOLUTE` | `0.15` | Minimum absolute difference between the local peak (before) and the trough. |
| **Relative drop** | `SATELLITE_DROP_RELATIVE` | `0.15` | Minimum relative difference: `(peak − trough) / peak ≥ 0.15`. |
| **Noise floor** | *hard-coded* | `0.05` | Movements smaller than this are treated as noise and never become a drop candidate. |

Both the absolute **and** relative thresholds must be met for a candidate to be
recorded as a drop.

---

## 3. Drop classification (explanations only — never a diagnosis)

Every detected drop receives **one** of the following `classification` values.
The API **never** labels a drop as "harvest", "drought", "disease" or "crop
failure" based on the vegetation index alone.

| Classification | Label shown to farmer | When it is chosen |
|----------------|----------------------|-------------------|
| `TRANSIENT_DIP` | "Transient dip or noisy observation" | Drop magnitude is small relative to recent variability, or the gap around the drop is large (suggesting an interpolation artefact). |
| `POSSIBLE_HARVEST` | "Possible harvest or clearing" | Sharp drop that does **not** recover green-up, with timing consistent with a harvest/clearing window. |
| `POSSIBLE_HEAT_OR_DRY_STRESS` | "Possible heat or dry stress" | Drop coincides with a period of high temperature (`tempMax7d ≥ SATELLITE_HEAT_TEMP_MAX_C`) and low rainfall (`rainfall14d ≤ SATELLITE_DRY_RAIN_14D_MM`). |
| `GRADUAL_DECLINE` | "Gradual vegetation decline" | The drop is part of a sustained downward trend over multiple observations, not a single sharp event. |
| `UNCLEAR` | "Unclear or insufficient evidence" | None of the above patterns fit with enough confidence. |

**Evidence attached to each drop** (`evidence` is a list of human-readable strings
on the API; each item names the number that drove the call):
- the z-score of the drop against local variability;
- the gap in days between the observations bracketing the drop (large gaps ⇒ lower confidence);
- the rainfall totals before the drop, when modelled weather covers that window;
- the classification rule that fired, with its numeric trigger.

`weatherContext` is the window around the drop and is a sibling of `evidence`
on the drop object (`{"available":true,"days":14,"rainfallMm":…,"tempMaxC":…,"tempMinC":…,"soilMoisturePct":…,"source":"Open-Meteo","dataType":"MODELLED","periodStart":…,"periodEnd":…}`),
or `{"available":false,"reason":"…"}` when no modelled weather covers the period.

**Confidence levels:** `HIGH` / `MEDIUM` / `LOW` — derived from evidence strength
(gap size, weather alignment, recovery clarity). A `LOW` confidence drop still
appears in the list so the farmer can see it, but the `nextStep` will be
generic ("Walk the plot and compare with field conditions").

**Next step examples:**
- `TRANSIENT_DIP` → "No action needed; this dip is within normal variability."
- `POSSIBLE_HARVEST` → "If you harvested or cleared this plot, record it in the event log so the label is verified."
- `POSSIBLE_HEAT_OR_DRY_STRESS` → "Check soil moisture in the field; compare with the weather forecast."
- `GRADUAL_DECLINE` → "Monitor weekly; consider nutrient or water management if the trend continues."
- `UNCLEAR` → "Walk the plot and compare with field conditions."

---

## 4. Alert rules (deduplicated by `alert_key`)

Alerts are created from drops and from observation/weather gaps. Each alert
has a stable `alert_key` so the **same ongoing event never spawns a second
alert** on a later analysis run. A dismissed alert is **never resurrected**.

| Alert type | `alert_key` pattern | Severity | Trigger |
|------------|---------------------|----------|---------|
| `NDVI_DECLINE` | `NDVI_DECLINE:{date}` | `WARNING` | The most recent drop is not a gradual decline — i.e. a localised excursion. The body names the possible explanation. |
| `POSSIBLE_STRESS` | `POSSIBLE_STRESS:{date}` | `WARNING` | The latest NDVI is below `SATELLITE_STRESS_NDVI` (default 0.30). An indicator of low vigour, not a diagnosis. |
| `NO_RECOVERY` | `NO_RECOVERY:{date}` | `WARNING` | At least `SATELLITE_NO_RECOVERY_DAYS` (default 21) days after the drop, recovery is still below 40 % of the pre-drop value. |
| `STALE_OBSERVATION` | `STALE_OBSERVATION:{date}` | `INFO` | Newest observation older than `SATELLITE_STALE_DAYS` (default 10). The body states the age so an old image is never presented as live. |
| `NO_OBSERVATION` | `NO_OBSERVATION` | `INFO` | No usable observation exists for the plot at all. |
| `SATELLITE_WEATHER_RISK` | `SATELLITE_WEATHER_RISK:{date}` | `WARNING` | 14-day rainfall below `SATELLITE_DRY_RAIN_14D_MM` (default 10 mm) **and** 7-day max temperature at or above `SATELLITE_HEAT_TEMP_MAX_C` (default 32 °C). Weather is modelled. |
| `GREEN_UP` | `GREEN_UP:{date}` | `INFO` | The growth cycle estimate detects a green-up date. |

**Severity → Notification:**
- `INFO` → no push notification, visible in the alert list only.
- `WARNING` / `CRITICAL` → creates a notification (if the farmer has satellite
  notifications enabled) via the existing notifications module.

**Statuses:** `OPEN` (default) → `ACKNOWLEDGED` (farmer saw it) → `DISMISSED`
(farmer decided it's not actionable). `DISMISSED` is terminal — a later run
will **not** flip it back to `OPEN`. The status endpoint takes an **action**
(`{"action":"ACKNOWLEDGE" | "DISMISS" | "REOPEN"}`), not a target status.

**Configurable + documented:** every threshold in the tables above is an
environment variable in `.env.example`, and `GET /satellite/overview` and
`GET /satellite/alerts` return the **active** values under `thresholds` so the
app can show exactly which numbers fired an alert (`activeThresholds()` in
`satellite.monitoring.service.js`).

---

## 5. Growth-cycle estimation

The engine fits a smooth curve to the (irregular) observation series and
extracts phenological dates. All dates are **estimates** and carry a confidence
label.

| Phase | How it is found | Confidence factors |
|-------|-----------------|---------------------|
| **Green-up** (`greenUpOn`) | First smoothed reading that clears the early-season baseline by ≥ 0.10 **and** keeps going (the next reading is also above it). The first 10 % of the record is treated as the dormant quiet period so a noisy early point is not called green-up. | More observations in the rise window ⇒ higher confidence. |
| **Peak** (`peakOn`) | The maximum of the **full** smoothed series (not a decimated one), so the date of the highest reading is never dropped. | Sharp, well-sampled peak ⇒ `HIGH`; broad/flat or sparsely sampled ⇒ `MEDIUM`/`LOW`. |
| **End of season** (`endOn`) | Last reading that falls below the amplitude baseline before the trailing gap. | Clear descent with observations ⇒ higher; long gap after peak ⇒ lower. |

The thresholds and strategy are returned with the cycle: `strategy` names the
composite-period method (every second valid observation), `baseline`,
`amplitude` and `peakValue` are the numbers the rules used, and `evidence` is a
list of human-readable strings. If there are fewer than `minCycleReadings`
(default **4**) valid observations, the engine returns `available:false` with a
`reason` instead of guessing dates. Every date is an **estimate** interpolated
between satellite passes, and the API says so.

---

## 6. Model status (training readiness)

The API reports `modelStatus` so the UI can be honest about whether a learned
classifier is active.

| Field | Meaning |
|-------|---------|
| `trained` | Always `false` in this release. No model has been trained or validated, so none is claimed. |
| `readyToTrain` | `true` once `labelledEvents ≥ requiredLabels` (30). Readiness only — it does not mean a model exists. |
| `labelledEvents` | Count of **confirmed** farmer events available as training labels (`source='FARMER' AND confirmed`). |
| `requiredLabels` | The documented minimum (30). |
| `note` | Human-readable explanation, e.g. "Rule-based classifications are in use. 0 of 30 verified farmer events are available; model training is pending." |

**Rule-based classification** (the current default) uses the thresholds and
patterns in this document. A learned model would only be used after it has been
evaluated on a held-out set (separate train/eval, no leakage). The API will
never claim a model is "trained" or "accurate" unless that evaluation step has
passed; until then `trained` stays `false` and the note says training is
pending.

---

## 7. Weather source labelling

All weather data comes from **Open-Meteo** (free, no API key). The response
always includes:

```json
{ "available": true, "source": "Open-Meteo", "dataType": "MODELLED" }
```

If `WEATHER_ENABLED=false` or the provider fails, the response reports
unavailability (`available:false` with a `reason`) rather than a series of
invented values. The `weather` endpoint wraps this as
`{plotId, weather, source, dataType}`. **No values are ever invented.**

`soilMoisturePct` is already a percentage (top-soil moisture, 0–7 cm) — the
client must not multiply it by 100.

---

## 8. Geometry rules (for the boundary preview / set endpoints)

Both endpoints take the boundary nested under `boundary`, exactly as the app
draws it:

```jsonc
// Method A - rotated rectangle
{ "boundary": { "method": "RECTANGLE", "centreLat": -24.65, "centreLon": 25.12,
                "widthM": 100, "lengthM": 113, "bearingDeg": 30 } }
// Method B - polygon ring
{ "boundary": { "method": "POLYGON",
                "vertices": [ {"lat":-24.65,"lon":25.12}, {"lat":-24.66,"lon":25.13},
                              {"lat":-24.67,"lon":25.12} ] } }
```

| Method | Parameters | Validation |
|--------|------------|------------|
| `RECTANGLE` | `centreLat`, `centreLon`, `widthM`, `lengthM`, `bearingDeg` | Width/length positive (≤ 100 000 m), bearing −1080…1080 accepted and normalised to 0–360. Area = width × length. |
| `POLYGON` | `vertices [{lat,lon}…]` (min 3, max 500) | ≥ 3 unique vertices, no self-intersections (bow-tie rejected), no sliver rings, plausible size. |

Responses carry the **server-measured** geometry:
`{valid, method, reason, vertexCount, areaM2, areaHectares, perimeterM, sideLengthsM,
centroid:{latitude,longitude}, vertices:[{lat,lon}]}`. When the ring cannot be
measured, `valid:false` with a `reason` — never a guessed area.

Area is computed by a local tangent-plane projection (WGS-84 mean radius) +
shoelace formula — **not** a bounding-box approximation, in
`services/geometry.js`. The Android client sends the drawn boundary and displays
the server's numbers; it does not compute area locally.

`PUT /satellite/plots/{id}/boundary` may include a sibling `plotName` for
rename-on-save, and returns the compact plot ref, the geometry, and
`observationsInvalidated` (observations that covered the old outline are dropped
and recalculated, so old readings are never attributed to the new shape).

---

## 8a. Shared farm and plot records (one source of truth)

Satellite Monitoring and Farm Management are **two views of the same farm data**,
not two systems. There is exactly one plot table and one farm table:

| Concept | Where it lives |
|---------|----------------|
| The plot record | `farm_plots` (`backend/migrations/002_domain.sql`) — the only plot table. |
| The plot's owner | `farm_plots.farmer_id → users(id)`. |
| The plot's boundary | `farm_plots.geojson_boundary` (jsonb) + `centroid_lat` / `centroid_lng`. |
| The plot's area | `farm_plots.area_hectares`, measured server-side from the boundary. |
| The plot's stable id | `farm_plots.id` (server uuid) — the **one** plot id used everywhere. |

Satellite data is child rows keyed by that same id — there is **no**
`satellite_plots` table, no geojson-only registry, and no duplicate farm record:

- `plot_observations.plot_id → farm_plots(id)`
- `plot_events.plot_id → farm_plots(id)`
- `plot_alerts.plot_id → farm_plots(id)`

The Android app mirrors this: the Satellite screen's plot selector reads the same
`farm_plots` Room table (via the same `FarmPlotRepository`) that Farm Management
writes, so a plot created, renamed or reshaped on one screen appears in the other
without a second copy. Navigation passes the same `plotId`
(`farmDetail(plotId)` ↔ `satellite(plotId)`).

**Reshaping a plot invalidates the geometry-dependent results.** An observation is
an average over the pixels *inside the boundary as it was when the reading was
taken*; change the boundary and those numbers describe a shape that no longer
exists. So a boundary edit clears the stored `plot_observations` for that plot.
This rule is shared by every path that can move a boundary, so the same edit has
the same effect whichever screen made it:

| Path | Handler |
|------|---------|
| Online, Farm Management | `PATCH /farms/plots/:id` → `invalidateObservationsForPlot` |
| Online, Satellite | `PUT /satellite/plots/:id/boundary` → `invalidateObservationsForPlot` |
| Offline replay, Farm Management | `FarmPlot.afterUpdate` in `sync.entities.js` → same rule, inside the sync transaction |

Both online paths use `boundaryChanged(before, after)` first, so a re-save that
resends the identical ring is **not** destructive — history is only cleared on a
genuine geometry change. Events and alerts are deliberately **not** deleted: they
are the farmer's record of what happened and carry their own dates.

Reads resolve the *owner's* farmer id via `resolveOwnerFarmerId` (`records.service.js`):
a `FARM_OWNER` reads their own plots, an employed `FARMER` reads the employer's
plots, and any other role is refused. This keeps satellite reads aligned with
`GET /farms/plots` (`farmOwnerOrFarmer`). Writes — mapping/reshaping a boundary,
requesting a new NDVI computation, and creating/editing/deleting events and alert
states — stay `farmOwnerOnly`.

---

## 9. The colour map ("satellite sight")

`GET /satellite/colour-map` returns a per-pixel grid of the newest usable stored
observation — or of a **chosen date** — drawn as a colour map in the Satellite
screen's Overview tab.

### Choosing an observation date

The plot's history is what makes the feature useful, so the map is not locked to
the newest reading. The response lists every date the plot has in
`availableDates`, and the Overview tab renders those as chips (newest first, with
a "Newest" chip for the current picture). Passing `?date=YYYY-MM-DD` draws that
day instead:

- `observedOn` is the date drawn; `newestObservedOn` is the newest date the plot
  has; `isHistorical: true` means the two differ.
- The UI **says so** whenever a past date is shown ("You are looking at an earlier
  image from … It shows what the field looked like then, not now"), because an old
  picture must never pass as the current condition.
- A requested `date` that is **not in the stored series** is refused with
  `available: false`, `requestedDate` set and a plain `reason`. The server never
  substitutes the nearest day: showing a different observation under the requested
  label would be a fabricated result.
- Switching plots clears the chosen date, because the new plot has its own series
  and the old date would not exist on it.

### The honesty rule it is built around

The monitoring pipeline measures **one number per date per plot** — the mean index
of the pixels inside the boundary. It does not return a per-pixel raster. So the
grid is constructed to preserve that mean exactly:

- the **average of the in-boundary cells equals the measured plot mean** (that
  number is real); and
- the **pattern across the cells** is a deterministic texture generated from
  `sha256(plotId + date)`, **not** a per-pixel observation.

Every response therefore carries `variationSimulated: true` and a `note` saying so,
and the client is required to show:

> Read the average, not the pattern. The average of this picture is a measured
> field value. The pattern across it is a simulated texture, not a per-pixel
> measurement, so it cannot pinpoint a spot in your field.

Presenting the texture as a real image would be a fabricated reading, which the
project forbids. The texture is deterministic, so the same plot and date always
draw the same picture — a reload is not a different field.

### Cells that are `null`, not `0`

A cell whose centre falls outside the boundary is returned as `null`. The client
must leave it blank rather than painting it, so the plot's shape is visible.
Mapping `null` to `0` would invent bare soil along the plot edge.

### Derived from stored data, never a fresh fetch

The map is built from the **stored** series, not a provider call, so the picture
always agrees with the numbers on the same screen. If it triggered a fetch, the
image and the summary could disagree and the user would have no way to tell which
to trust.

### Flags

| Field | Meaning |
|-------|---------|
| `isSimulated` | The mean behind the picture is demonstration (MOCK) data |
| `variationSimulated` | Always `true` — the pattern is texture |
| `isStale` / `ageDays` | The newest reading is older than the staleness window |
| `coverage.validPixelPct` | Usable-pixel coverage of the underlying reading |
| `meanValue` | The measured field average; the grid's average equals this |

---

## 10. Farm-wide attention (`GET /satellite/alerts/by-farm`)

The per-plot alert list answers "what alerts does **this** plot have?". A farmer
with several plots also needs "which of my farms need me?", so this endpoint
returns **every** live plot, grouped by farm.

**What "farm" means here.** This schema has no separate farm entity: a plot
belongs directly to its owner (`farm_plots.farmer_id`), so the owner's account
*is* the farm and all of the owner's plots form one group, labelled with the
owner's name. The `farms[]` array is therefore today a single group — the shape
is kept so the client can render several groups without a change if a farm
entity is ever introduced. (The first release of this endpoint joined a `farms`
table and a `farm_plots.farm_id` column that were never created, which returned
HTTP 500; `tests/unit/alertsByFarmSchema.test.js` now pins the queries to the
real columns.)

Three attention states are reported, kept visibly distinct because they mean
different things:

| State | Field | Meaning |
|-------|-------|---------|
| Open alerts | `openAlertCount > 0` | The analysis engine raised something |
| Stale | `isStale` | No clear reading within `staleDays` |
| No data | `noData` | No usable observation at all (never read, or boundary just redrawn) |

**A plot with no data is not healthy.** It is returned with `noData: true`,
`needsAttention: true` and a plain-language `reason`, so a forgotten field cannot
look fine by being absent from the list. A plot in none of these states has
`needsAttention: false` and a null `reason`.

---

## 11. Changing thresholds

All thresholds are environment variables (see `backend/.env.example` and
`render.yaml`). To change them:

1. Update the value in the deployment environment (Render dashboard).
2. Redeploy — the new values are read on every analysis run (no code change).
3. The API returns the active thresholds in the `thresholds` object so the
   client always shows the numbers that actually applied.

**Do not** hard-code these numbers in the Android app — read them from the
overview response.

---

## 12. Dates must be calendar dates, never `Date` objects

A satellite reading has a *calendar date* — the day the satellite passed — with no
time and no timezone. The database stores it as a Postgres `date`.

**node-postgres parses a `date` column into a JavaScript `Date` at local midnight
by default.** That caused a real production failure on 2026-10-22, and the lesson
is worth keeping:

```
GET /satellite/overview  ->  200, but
  observations       : 0        (a series of 23 stored rows)
  summary.available  : false    "No valid NDVI reading is available"
  newestObservation  : "Thu Oct 22"
  note               : "...is Thu Oct 22, NaN day(s) old."
```

`String(row.observed_on).slice(0, 10)` produces `"Thu Oct 22"` for a `Date` — a
day-of-week string. It then failed the analysis engine's strict
`/^\d{4}-\d{2}-\d{2}/` check, so **every row was dropped** and a plot with a full
series looked empty. Two endpoints disagreed about the same plot.

The same mistake appeared in three places, which is why the fix is layered:

1. **`src/db/index.js` registers a `pg` type parser for OID 1082**, so a `date`
   now arrives as a plain `YYYY-MM-DD` string everywhere. This is the root fix.
2. **`normaliseDate()`** in `satelliteAnalysis.js` coerces a `Date`, an ISO
   string or a date-only string, and returns `''` for anything else — so a bad
   value drops one row instead of the whole series.
3. **`dateOnly()`** (`utils/serialize.js`) is used at every serialisation site.

### Why the timezone half matters more than the crash

A `Date` at local midnight is a *different instant* from UTC midnight. Formatted on
a UTC server, `2026-10-22` becomes `2026-10-21`. The observation silently moves to
the previous day — and an imagery date that drifts by a day is worse than an error,
because nothing looks wrong. A farmer comparing two readings would be misled about
which one changed. A `date` has no time component to lose, so the **text form is
the value**; `tests/unit/dbDateParsing.test.js` asserts this holds under
`Africa/Gaborone`, `UTC` and `Pacific/Kiritimati`.

The same class of bug hid `summary.dataAgeDays`: `String(now).slice(0, 10)` on the
`Date` the service passes produced `"Wed Oct 21"`, so the field was **always**
`null` in production and the client could never say how old a reading was.

---

## 13. Versioning

This document corresponds to the thresholds in `satelliteAnalysis.js` as of
the `DEFAULT_THRESHOLDS` export. When thresholds change, update this file in
the same commit.

## 14. The on-device farm map

The Satellite screen opens with an interactive Google map
(`ui/components/PlotMapView.kt`) that draws the **stored** plot boundaries — the same
GeoJSON Farm Management writes — as polygons tinted by each plot's **measured** mean
NDVI, using the single shared classifier in `core/util/NdviUtil.kt` (thresholds 0.20 /
0.45, §11). Rules:

- A plot with a stored boundary and `>= 3` corners is a polygon. A plot with only a
  centroid is a marker. **A marker is never converted into a polygon**: a single point
  does not define an area, and inventing one would be a fabricated survey. The plot card
  says "no boundary stored yet" and points at the boundary-creation flow.
- Only the plot's **average** is drawn as a tint. The per-pixel *pattern* the backend
  generates for the colour map is `variationSimulated: true` (§9) and is never rendered
  onto the map as imagery — doing so would present generated texture as a measurement.
- A simulated (MOCK) mean is tinted like any other reading but is labelled `DEMO`, never
  silently made to look real.
- A missing Maps key, or a key Google rejects ("Maps SDK for Android" not enabled for the
  project), renders an explained panel naming the cause. It is never a silent blank grid.

## 15. Offline overview cache (Room v3)

`GET /satellite/overview` is mirrored on the device in `satellite_overview_cache`
(`SatelliteOverviewCacheEntity`), one row per plot, holding the raw response JSON plus
two **separate** timestamps:

- `observed_on` — the server's own newest observation date. A satellite pass.
- `cached_at` — when the device last received the payload. A local write.

These must never be conflated. When the screen falls back to the cache it shows the
original `observed_on` and a **Cached** chip with "saved on this device <relative time>",
and it never re-dates the observation to today. Opening the screen does not create an
observation. If the request cannot reach the server and no cached row exists, the state is
**Offline** / **No observation available** — never a fresh-looking number.

Cache states shown to the farmer, in this precedence: Updated, Cached, Offline,
No observation available, Update failed. A failed request that has a cached row is
"Cached", not "Update failed", because the farmer is looking at real stored data.

The migration is `MIGRATION_2_3` (`data/local/Migrations.kt`), covered by
`SatelliteCacheMigrationTest`, which asserts both that the new table exists and that an
existing `farm_plots` row survives the upgrade.
