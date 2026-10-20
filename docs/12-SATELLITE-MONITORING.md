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

## 9. Changing thresholds

All thresholds are environment variables (see `backend/.env.example` and
`render.yaml`). To change them:

1. Update the value in the deployment environment (Render dashboard).
2. Redeploy — the new values are read on every analysis run (no code change).
3. The API returns the active thresholds in the `thresholds` object so the
   client always shows the numbers that actually applied.

**Do not** hard-code these numbers in the Android app — read them from the
overview response.

---

## 10. Versioning

This document corresponds to the thresholds in `satelliteAnalysis.js` as of
the `DEFAULT_THRESHOLDS` export. When thresholds change, update this file in
the same commit.