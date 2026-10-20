# AgriConnect REST API Contract v1

Base URL: `https://<host>/api/v1`  ·  Transport: **HTTPS only**  ·  Payloads: **JSON** (GeoJSON for
boundaries)  ·  Auth: **JWT bearer** (short-lived access token + rotating refresh token).

Machine-readable spec: [`backend/openapi.yaml`](../backend/openapi.yaml).

---

## 0. Conventions

### Authentication
```
Authorization: Bearer <accessToken>
```
Access token TTL **15 min**; refresh token TTL **30 days** with **rotation** (each refresh returns a
new refresh token and revokes the old one). Refresh tokens are stored **hashed** server-side.

### Idempotency
`POST /auth/register`, `POST /orders` and `POST /sync/push` accept an optional
`Idempotency-Key` header. Repeated calls with the same key return the original result — this is how
double-taps and retry-after-network-failure are prevented from creating duplicates.

### Error envelope (never leaks internals)
```json
{
  "error": { "code": "VALIDATION_ERROR", "message": "Human readable, safe.",
             "details": [ { "field": "phoneNumber", "message": "Required" } ] },
  "requestId": "0f8c…"
}
```
Codes: `VALIDATION_ERROR 400` · `UNAUTHENTICATED 401` · `INVALID_CREDENTIALS 401` ·
`TOKEN_EXPIRED 401` · `ACCOUNT_UNVERIFIED 403` · `ACCOUNT_SUSPENDED 403` · `FORBIDDEN 403` ·
`NOT_FOUND 404` · `CONFLICT 409` · `DUPLICATE_ACCOUNT 409` · `RATE_LIMITED 429` ·
`INTERNAL 500`.

### Role guard
Every protected route declares allowed roles. Requests from a role that is not permitted receive
`403 FORBIDDEN`. **The UI hiding a control is never the enforcement point.**

### Rate limiting
`/auth/*` 10 req / 15 min / IP+identifier · `/sync/push` 120 req / 15 min / user ·
public reads 300 req / 15 min / IP.

### Pagination
`?page=1&pageSize=20` (max 100) → `{ "items":[…], "page":1, "pageSize":20, "total":n, "hasMore":bool }`

---

## 1. Shared object shapes

```jsonc
// User
{ "userId":"uuid", "fullName":"...", "phoneNumber":"+267...", "email":null,
  "role":"FARM_OWNER|FARMER|BUYER", "accountStatus":"REGISTERED|UNVERIFIED|VERIFIED|SUSPENDED|PENDING_DELETION|DELETED",
  "avatarUrl":null, "notificationPreferences":{ "orders":true,"sync":true,"satellite":true,"marketplace":true },
  "createdAt":"ISO8601", "updatedAt":"ISO8601", "verifiedAt":null }

// Sync meta present on all synchronisable entities
{ "syncStatus":"PENDING|SYNCED|CONFLICT|FAILED", "createdAt":"…", "updatedAt":"…", "serverUpdatedAt":"…" }
```

All money fields are **BWP**, `NUMERIC(14,2)`, serialised as a JSON number with 2 decimals.

---

## 2. Endpoints

### 2.1 Auth — `/auth`

| Method | Path | Roles | Body | Success |
|--------|------|-------|------|---------|
| POST | `/auth/register` | public | `{fullName, phoneNumber, email?, password, role, acceptTerms:true, acceptPrivacy:true}` | `201 {user, accessToken, refreshToken, expiresIn}` |
| POST | `/auth/login` | public | `{identifier, password}` | `200 {user, accessToken, refreshToken, expiresIn}` |
| POST | `/auth/refresh` | public | `{refreshToken}` | `200 {accessToken, refreshToken, expiresIn}` |
| POST | `/auth/logout` | any | `{refreshToken?}` | `204` |
| POST | `/auth/logout-all` | any | – | `204` |
| GET  | `/auth/me` | any | – | `200 {user}` |
| POST | `/auth/password/forgot` | public | `{identifier}` | `202 {message:"If that account exists, a code has been sent."}` |
| POST | `/auth/password/verify` | public | `{identifier, code}` | `200 {resetToken}` |
| POST | `/auth/password/reset` | public | `{resetToken, newPassword}` | `200 {message}` |
| POST | `/auth/verification/request` | public | `{identifier}` | `202 {message}` |
| POST | `/auth/verification/confirm` | public | `{identifier, code}` | `200 {message}` |

Rules:
- Registration **never** trusts a client-supplied role beyond the three allowed values, and rejects
  any attempt to set privileged flags. Duplicate phone (or supplied email) → `409 DUPLICATE_ACCOUNT`.
- Password policy: ≥ 8 chars, ≥ 1 letter, ≥ 1 number. Rejected client- **and** server-side.
- `forgot` is **always** generic (no account-existence disclosure) and rate-limited.
- Reset tokens: random 32 bytes, stored **hashed**, **single-use**, TTL **15 min**; on success all
  refresh tokens for that user are revoked.
- Verification codes: 6 digits, stored hashed, TTL 10 min, max 5 attempts.

### 2.2 Profile — `/users/me`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| GET | `/users/me` | any | full profile |
| PATCH | `/users/me` | any | `{fullName?, email?}`. Changing email ⇒ `accountStatus→UNVERIFIED` + verification code. **`role` is ignored/rejected.** |
| POST | `/users/me/password` | any | `{currentPassword, newPassword}` → revokes other sessions |
| PUT | `/users/me/notification-preferences` | any | `{orders?,sync?,satellite?,marketplace?}` |
| POST | `/users/me/avatar` | any | multipart image ≤ 2 MB (jpeg/png/webp) |
| GET | `/users/me/export` | any | machine-readable personal-data export |

### 2.3 Account deletion — `/users/me/deletion-request` + public page

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| POST | `/users/me/deletion-request` | any | `{password, reason?}` — re-authentication required. Sets `accountStatus→PENDING_DELETION`, revokes all sessions, starts grace period. |
| GET | `/users/me/deletion-request` | any (auth) | current request + `effectiveAt` |
| POST | `/users/me/deletion-request/cancel` | any (auth) | cancel during grace period |
| POST | `/public/account-deletion` | public | store-required external deletion page: `{identifier, reason?}` → always `202` generic |

Retention (see `03-DATA-RETENTION-AND-DELETION.md`): personal data (name, phone, email, avatar,
notification prefs) is **anonymised immediately** at the end of the grace period; de-identified
financial/order records are retained for **7 years** for legal/tax reasons and are explicitly
disclosed to the user before confirmation.

### 2.4 Farms & plots — `/farms`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| GET | `/farms/plots` | farm owner, farmer | the farm's plots, newest first |
| POST | `/farms/plots` | farm owner | `{plotName, areaHectares, geoJsonBoundary, clientId?, updatedAt}` |
| GET | `/farms/plots/{id}` | farm owner, farmer | own farm only |
| PATCH | `/farms/plots/{id}` | farm owner | own only; returns the plot plus `observationsInvalidated` |
| DELETE | `/farms/plots/{id}` | farm owner | own only |

A farmer who works the land reads the plots of the owner who employs them, so field
work can be recorded against the right plot; only the farm owner maps and edits them.

**Shared with Satellite Monitoring.** These are the same `farm_plots` rows the Satellite
screen analyses — one plot table, one `plotId`. A `PATCH` that actually moves the
boundary clears that plot's stored `plot_observations` (which were averaged over the old
outline) and reports the count in `observationsInvalidated`; a PATCH that resends the
identical ring does not. `PUT /satellite/plots/{id}/boundary` and the offline sync replay
apply the same rule, so the same edit has the same effect whichever screen made it. See
`docs/12-SATELLITE-MONITORING.md` §8a.

### 2.5 Farm records

| Method | Path | Roles |
|--------|------|-------|
| GET/POST | `/farm/harvests` · `/farm/harvests/{id}` | farm owner, farmer |
| GET/POST | `/farm/livestock` · `/farm/livestock/{id}` | farm owner, farmer |
| GET/POST | `/finance/sales` · `/finance/sales/{id}` | **farm owner only** |
| GET/POST | `/finance/expenses` · `/finance/expenses/{id}` | **farm owner only** |
| GET | `/finance/summary?from&to` | **farm owner only** → `{totalSales,totalExpenses,netBalance,series:[{period,sales,expenses}]}` |

A farmer who works the land requesting `/finance/*` → **`403`** (explicitly tested).

### 2.6 Satellite — `/satellite`

The satellite API has two layers:

**Legacy NDVI request/response** (`/satellite/ndvi*`): on-demand Sentinel-2 NDVI for a plot.
**Monitoring dashboard** (`/satellite/overview`, `/satellite/events`, `/satellite/alerts`, …): a
unified, plot-centric screen that combines a clean observation cache, growth-cycle analysis,
farmer event log, deduplicated alerts, and weather — all in one response.

#### 2.6.1 Legacy NDVI endpoints

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| POST | `/satellite/ndvi` | farm owner | `{plotId, geoJsonBoundary?}` → `201 {ndviId, plotId, satelliteDate, meanNdviValue, stressLevel, status, compressedVectorJson, isCached:false, advice}` |
| GET | `/satellite/ndvi?plotId=` | farm owner, farmer | history, newest first — **no `advice` on history items** |
| GET | `/satellite/ndvi/latest?plotId=` | farm owner, farmer | newest record, or `ndvi: null` |
| GET | `/satellite/ndvi/{id}` | farm owner, farmer | one record |

Reads are available to whoever may read the plot (matching `GET /farms/plots`); requesting a
**new** computation (`POST /satellite/ndvi`) stays the owner's job because it consumes provider
quota and needs the boundary.

Backend pipeline: validate polygon → Earth Engine `Sentinel-2` → Band 8 (NIR) / Band 4 (RED) →
`NDVI=(NIR−RED)/(NIR+RED)` → classify with the documented thresholds → return compressed vector
alert. Cloud cover > 80 % ⇒ `status:"FAILED"`, `meanNdviValue:null`. NDVI is **not** presented as a
definitive agronomic diagnosis.

##### The `advice` block (legacy)

Once a reading is generated, the response also carries `advice`: what the number means, what to
do about it, what to watch for in the field, how far the measurement can be trusted, the published
scale it sits on, and what a vegetation index cannot say. It is built **once, server-side**
(`backend/src/services/ndviAdvice.js`) so every client shows the same guidance for the same
observation, and it appears on the fresh computation, on `latest` and on a single record — never on
paginated history, where it would cost two extra queries per item.

How it is derived, and what it deliberately never does:

- **Trend** — compared with the plot's *previous* generated reading, and only a movement larger than
  the noise floor (`0.05`) counts as improving or declining; otherwise the label is "stable". With no
  previous reading it says so ("first reading for this plot") instead of pretending it is stable.
- **Priority** — `NOW` (high stress), `SOON` (moderate), `ROUTINE` (healthy). The wording of each
  action is investigative ("walk the plot", "check soil moisture"), because NDVI cannot name the
  cause of a low reading.
- **Confidence** — about the *measurement*, never about the crop: `LOW` for a simulated (`source:
  MOCK`) or heavily cloud-covered observation, `HIGH` for a clear real one, `MEDIUM` otherwise.
- **Never invents data** — the value echoed in `assessment.value` is the stored `meanNdviValue`; a
  `FAILED` or in-flight record has `advice: null`, and the mock mode is labelled as demonstration
  data in both the response and the UI.

#### 2.6.2 Monitoring dashboard endpoints (new)

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| GET | `/satellite/overview?plotId=&days=` | farm owner | **Complete dashboard**: geometry, observation series (NDVI + EVI, raw + smoothed), growth-cycle estimates, detected drops with explanations, weather, alerts, quality metrics, model status, thresholds. `days` and `daysBack` are both accepted (15–730, either name). |
| POST | `/satellite/boundary/preview` | farm owner | Validate RECTANGLE or POLYGON geometry; returns the server-computed geometry (area/perimeter/centroid) or `valid:false` with a `reason`. No persistence. |
| PUT | `/satellite/plots/{plotId}/boundary` | farm owner | Persist RECTANGLE (centre, width, length, bearing) or POLYGON (vertices, min 3). Returns the compact plot ref, the geometry, and how many observations were invalidated. |
| GET | `/satellite/weather?plotId=&days=` | farm owner | Modelled weather from **Open-Meteo** (rainfall, temp min/max/avg, soil moisture), wrapped as `{plotId, weather, source, dataType}`. `source` and `dataType` always labelled. |
| GET | `/satellite/events?plotId=&page=&pageSize=` | farm owner | Paged farmer event log **and** system-detected drops (newest first), including the count of telemetry events kept instead of a detectable drop. |
| POST | `/satellite/events` | farm owner | Add farmer event (idempotent via `clientId`). Supplying `detectedEventId` merges the entry with the detected drop for that date and returns `mergedFromDetected`. |
| PATCH | `/satellite/events/{eventId}` | farm owner | Correct kind, confirm, add notes, adjust date. Supplying `kind` counts as confirmation (verified label). Works on `SYSTEM` rows — that is the confirm/correct flow. |
| DELETE | `/satellite/events/{eventId}` | farm owner | Delete a farmer event. `SYSTEM` rows are refused (`409`) — correct them instead. |
| GET | `/satellite/alerts?plotId=&status=&page=&pageSize=` | farm owner | Paged, deduplicated alerts (by `alert_key`) plus the active `thresholds`. Statuses: `OPEN`, `ACKNOWLEDGED`, `DISMISSED`. |
| POST | `/satellite/alerts/{alertId}/status` | farm owner | `{"action":"ACKNOWLEDGE" | "DISMISS" | "REOPEN"}`. A `DISMISS` is terminal: a later analysis run never resurrects it. |
| GET | `/satellite/plots/latest` | farm owner | Compact per-plot summary for the dashboard: newest observation date/age, NDVI/EVI, quality, simulation flag and source. |

**Monitoring data contracts** (these match the server responses exactly; see
`backend/src/modules/satellite/satellite.monitoring.service.js`)

```jsonc
// GET /satellite/overview response (abridged) — note `plot` is a compact ref,
// `summary` is NDVI-only, and EVI statistics live in `eviSummary`.
{
  "plot": { "plotId":"...", "plotName":"North field", "areaHectares":1.13,
            "centroidLat":-24.65, "centroidLng":25.12, "geoJsonBoundary":{...}, "imageUrl":null },
  "geometry": { "valid":true, "method":"RECTANGLE", "reason":null, "vertexCount":4,
                "areaM2":11342, "areaHectares":1.13, "perimeterM":432,
                "sideLengthsM":[100,113,100,113],
                "centroid":{"latitude":-24.65,"longitude":25.12},
                "vertices":[ {"lat":-24.65,"lon":25.12}, ... ] },
  "observationStatus": { "isCached":false, "isSimulated":true, "newestObservation":"2026-07-15",
                         "newestObservationAgeDays":5, "provider":{"source":"MOCK","error":null},
                         "providerError":null, "note":"...", "returned":12, "coversDays":365,
                         "boundaryUsable":true },
  "observations": [ { "date":"2026-07-15", "ndvi":0.42, "evi":0.38,
                      "smoothedNdvi":0.41, "smoothedEvi":0.37, "validPct":87.3,
                      "cloudPct":12, "pixels":1245, "quality":"GOOD", "isOutlier":false,
                      "isSimulated":true, "source":"MOCK" }, ... ],
  "summary": {
    "available":true, "reason":null, "index":"NDVI", "count":12,
    "latest":{ "date":"2026-07-15", "ndvi":0.42, "evi":0.38 },
    "previous":{ "date":"2026-06-20", "ndvi":0.47, "evi":0.44 },
    "min":0.31, "max":0.68, "average":0.49, "change":-0.05, "changePct":-10.6,
    "trend":"DECLINING", "dataAgeDays":5,
    "firstDate":"2025-08-01", "lastDate":"2026-07-15",
    "coverageFrom":"2025-08-01", "coverageTo":"2026-07-15"
  },
  "eviSummary": { "available":true, "index":"EVI", "count":12, "min":0.28, "max":0.61,
                  "average":0.44, "change":-0.04, "trend":"DECLINING", "dataAgeDays":5 },
  "growthCycle": { "available":true, "reason":null, "greenUpOn":"2026-03-10",
                   "peakOn":"2026-05-15", "endOn":"2026-08-20", "peakValue":0.74,
                   "baseline":0.28, "amplitude":0.46, "confidence":"MEDIUM",
                   "strategy":"smoothed-threshold", "evidence":["..."] },
  "drops": [ { "date":"2026-07-10", "previousDate":"2026-06-20", "daysBetween":20,
               "ndviBefore":0.54, "ndviAfter":0.42, "eviBefore":0.5, "eviAfter":0.39,
               "magnitude":0.12, "relativePct":22.2, "quality":"GOOD",
               "classification":"POSSIBLE_HEAT_OR_DRY_STRESS",
               "label":"Possible heat or dry stress",
               "explanation":"...", "evidence":["...","..."],
               "confidence":"MEDIUM", "nextStep":"Check soil moisture in the field.",
               "weatherContext":{ "available":true, "days":14, "rainfallMm":0,
                                  "tempMaxC":34.2, "tempMinC":18, "soilMoisturePct":21,
                                  "source":"Open-Meteo", "dataType":"MODELLED",
                                  "periodStart":"2026-06-26", "periodEnd":"2026-07-10" },
               "disclaimer":"...", "recoveryFraction":0.3, "recoveredOn":null,
               "gradual":false, "detectedEventId":"uuid" } ],
  "weather": { "available":true, "reason":null, "source":"Open-Meteo", "dataType":"MODELLED",
               "cached":true, "fetchedAt":"2026-07-16T06:00:00Z", "note":"...",
               "daily":[ { "date":"2026-07-15", "rainfallMm":0, "tempMinC":18, "tempMaxC":32,
                           "tempMeanC":25, "soilMoisturePct":21 }, ... ],
               "summary":{ "periodStart":"2026-07-09", "periodEnd":"2026-07-15",
                           "rainfall7dMm":0, "rainfall14dMm":3.2, "tempMax7dC":34.2,
                           "tempMin7dC":11.5, "avgTemp7dC":24.1, "soilMoistureMeanPct":21 } },
  "alerts": [ { "alertId":"...", "plotId":"...", "farmerId":"...",
                "alertKey":"NDVI_DECLINE:2026-07-10", "alertType":"NDVI_DECLINE",
                "severity":"WARNING", "title":"NDVI drop detected", "body":"...",
                "status":"OPEN", "raisedAt":"2026-07-16T08:12:00Z", "resolvedAt":null,
                "acknowledgedAt":null, "dismissedAt":null,
                "createdAt":"...", "updatedAt":"..." } ],
  "quality": { "readingsReceived":14, "readingsUsable":12, "readingsRejected":2,
               "outliers":0, "note":"..." },
  "modelStatus": { "trained":false, "readyToTrain":false, "labelledEvents":0,
                   "requiredLabels":30, "note":"..." },
  "thresholds": { "dropAbsolute":0.12, "dropRelative":0.18, "staleDays":30,
                  "stressNdvi":0.35, "noRecoveryDays":21, "dryRain14dMm":5,
                  "heatTempMaxC":33, "maxGapDays":45, "minCycleReadings":6 },
  "thresholdDocumentation": "docs/12-SATELLITE-MONITORING.md",
  "disclaimer": "Vegetation indices indicate vegetation condition. They are not direct proof of yield, disease or the cause of a change.",
  "generatedAt": "2026-07-16T08:12:00.000Z"
}
```

```jsonc
// ALL paged collections use the same envelope: /satellite/events, /satellite/alerts,
// /satellite/plots/latest. Defaults: page=1, pageSize=20, max pageSize=100.
{ "items": [ ... ], "page": 1, "pageSize": 20, "total": 42, "hasMore": true }
// /satellite/alerts additionally repeats the active thresholds:
{ "items": [ ... ], "page": 1, "pageSize": 20, "total": 3, "hasMore": false,
  "thresholds": { "dropAbsolute":0.12, ... } }
```

```jsonc
// POST /satellite/events request (farmer log)
{ "plotId":"uuid", "eventDate":"2026-07-15", "kind":"HARVEST",
  "notes":"Maize harvest completed",
  "clientId":"device-generated-uuid", "detectedEventId":"uuid-of-detected-drop" }
```

```jsonc
// POST /satellite/alerts/{id}/status — an ACTION, not a target status
{ "action": "ACKNOWLEDGE" }   // or "DISMISS" or "REOPEN"
```

```jsonc
// POST /satellite/boundary/preview request — the boundary is nested under `boundary`
{ "boundary": { "method":"RECTANGLE", "centreLat":-24.65, "centreLon":25.12,
                "widthM":100, "lengthM":113, "bearingDeg":30 } }
// ...or, Method B:
{ "boundary": { "method":"POLYGON",
                "vertices":[ {"lat":-24.65,"lon":25.12}, {"lat":-24.66,"lon":25.13},
                             {"lat":-24.67,"lon":25.12} ] } }
// PUT /satellite/plots/{id}/boundary takes the same body plus an optional sibling
// `plotName`: { "boundary": {...}, "plotName": "North field" }
```

**Key guarantees**

- **No fabricated values**: every number comes from a real provider (Sentinel-2, Open-Meteo) or is
  explicitly labelled `isSimulated:true` with `source:"MOCK"`. The provider failure path is visible
  (`providerError`).
- **Deduplication**: alerts are keyed by `alert_key` (e.g. `NDVI_DECLINE:2026-07-10`). One ongoing
  event = one alert row. A dismissed alert is **never** recreated by a later analysis run.
- **Honest classification**: detected drops are *explained*, never diagnosed. The restricted
  `classification` values are `TRANSIENT_DIP`, `POSSIBLE_HARVEST`, `POSSIBLE_HEAT_OR_DRY_STRESS`,
  `GRADUAL_DECLINE`, `UNCLEAR`. A drop alone is never labelled harvest, drought, disease or crop
  failure.
- **Verified labels only**: `plot_event_label_counts` view counts **confirmed farmer events only**
  (`source='FARMER' AND confirmed`). System suggestions (`source='SYSTEM'`) are never used as
  training labels. `modelStatus.trained` stays `false` and `modelStatus.readyToTrain` reports
  readiness until enough verified labels exist — no model is claimed without validation.
- **Weather truthfulness**: if `WEATHER_ENABLED=false` or the provider fails, the response reports
  unavailability (`available:false` with a `reason`) — no invented values.
- **A stale image is never "live"**: `observationStatus.newestObservation` +
  `newestObservationAgeDays` state when the newest clear pass actually was, and the app shows it.


### 2.7 Marketplace — `/marketplace`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| GET | `/marketplace/listings` | any role | **only `PUBLISHED` by default**; `?search=&crop=&minPrice=&maxPrice=&sort=price_asc|price_desc|recent&page=` |
| GET | `/marketplace/listings/{id}` | any role | PUBLISHED, or own listing in any state |
| GET | `/marketplace/my-listings` | farm owner | all own states |
| POST | `/marketplace/listings` | farm owner | creates `DRAFT` |
| PATCH | `/marketplace/listings/{id}` | farm owner | own only |
| POST | `/marketplace/listings/{id}/publish` | farm owner | `DRAFT/PENDING_PUBLISH→SYNCING→PUBLISHED` |
| POST | `/marketplace/listings/{id}/archive` | farm owner | → removed from public view |

Lifecycle: `DRAFT → PENDING_PUBLISH → SYNCING → (RETRY) → PUBLISHED → SOLD`.
**A listing is never publicly visible before the server confirms `PUBLISHED`.**

**Units** (`unit`, validated against `ListingUnit` in `openapi.yaml`): `kg`,
`tonne`, `bag`, `crate`, `bunch`, `litre` for produce, plus the livestock units
`head`, `herd` and `flock`. `head` counts single animals (`20 head of cattle`);
`herd` and `flock` sell a whole group as one lot (quantity `1`, one price). The
same list lives in the DB CHECK constraint (`migrations/003` + `008`),
`LISTING_UNITS` in the marketplace schema, and the sync entity registry — the three
must stay in step, so the sync path imports the API list rather than keeping a
private copy.

### 2.8 Orders — `/orders`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| POST | `/orders` | buyer | `{listingId, quantity, note?}` — transactional stock check; idempotent |
| GET | `/orders` | buyer (own) / farmer (on own listings) | |
| GET | `/orders/{id}` | buyer owner / farmer owner | |
| POST | `/orders/{id}/confirm` | farmer (owner) | `PENDING→CONFIRMED`, listing `→SOLD`, notifies buyer |
| POST | `/orders/{id}/reject` | farmer (owner) | `PENDING→CANCELLED`, stock restored |
| POST | `/orders/{id}/cancel` | buyer (owner) | only while `PENDING` |
| POST | `/orders/{id}/complete` | farmer (owner) | `CONFIRMED→COMPLETED` |

Workflow (v1.1 §9.4): buyer places order → `PENDING` → farmer notified → farmer confirms →
`CONFIRMED` + listing `SOLD`. **No payment processing.** Stock is held inside a DB transaction with
`SELECT … FOR UPDATE`; over-ordering → `409 CONFLICT`.

### 2.9 Notifications — `/notifications`

| Method | Path | Roles |
|--------|------|-------|
| GET | `/notifications?unreadOnly=` | any |
| PATCH | `/notifications/{id}/read` | owner |
| POST | `/notifications/read-all` | any |
| POST | `/devices` | any (register push token) |

### 2.10 Sync — `/sync`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| POST | `/sync/push` | all roles | see below |
| GET | `/sync/pull?since=ISO8601` | any | server-authoritative deltas |

**Push request**
```json
{ "items": [
  { "queueId":"local-uuid", "entityType":"SaleTransaction", "entityId":"local-uuid",
    "operation":"CREATE|UPDATE|DELETE", "payload":"{…}", "clientTimestamp":"ISO8601", "retryCount":0 } ],
  "deviceId":"…" }
```
**Push response**
```json
{ "serverTime":"ISO8601",
  "results":[
    { "queueId":"…", "status":"SYNCED",   "serverEntityId":"uuid", "serverUpdatedAt":"…" },
    { "queueId":"…", "status":"CONFLICT", "serverEntity":{…}, "serverUpdatedAt":"…" },
    { "queueId":"…", "status":"FAILED",   "reason":"VALIDATION_ERROR" } ] }
```
Rules:
- Server validates each item; **owned-entity check** → a user can never push another user's record.
- `CONFLICT` = the server copy is newer than `clientTimestamp` (Last-Write-Wins: the **cannot**
  resolve silently against the user — status becomes `CONFLICT` and the client re-pushes the
  timestamp-resolved entity, per v1.1 §9.5).
- Duplicate `queueId` (already processed) → returns the original result (idempotent).
- `FAILED` items: client increments `retryCount`, WorkManager exponential backoff
  `10s→20s→40s→80s…≤1h`.

---

## 3. HTTP status summary

`200` ok · `201` created · `202` accepted (async/generic) · `204` no content ·
`400` validation · `401` auth · `403` role/ownership/status · `404` not found ·
`409` conflict/duplicate · `429` rate limited · `500` internal (never leaks stack traces).

---

## 4. Versioning

URL-versioned (`/api/v1`). Breaking changes require `/api/v2`. Non-breaking additions are allowed in
v1. `GET /health` returns `{status, version, time}` and is unauthenticated.
