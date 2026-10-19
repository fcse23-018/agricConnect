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
| PATCH | `/farms/plots/{id}` | farm owner | own only |
| DELETE | `/farms/plots/{id}` | farm owner | own only |

A farmer who works the land reads the plots of the owner who employs them, so field
work can be recorded against the right plot; only the farm owner maps and edits them.

### 2.5 Farm records

| Method | Path | Roles |
|--------|------|-------|
| GET/POST | `/farm/harvests` · `/farm/harvests/{id}` | farm owner, farmer |
| GET/POST | `/farm/livestock` · `/farm/livestock/{id}` | farm owner, farmer |
| GET/POST | `/finance/sales` · `/finance/sales/{id}` | **farm owner only** |
| GET/POST | `/finance/expenses` · `/finance/expenses/{id}` | **farm owner only** |
| GET | `/finance/summary?from&to` | **farm owner only** → `{totalSales,totalExpenses,netBalance,series:[{period,sales,expenses}]}` |

A farmer who works the land requesting `/finance/*` → **`403`** (explicitly tested).

### 2.6 Satellite NDVI — `/satellite`

| Method | Path | Roles | Notes |
|--------|------|-------|-------|
| POST | `/satellite/ndvi` | farm owner | `{plotId, geoJsonBoundary}` → `201 {ndviId, plotId, satelliteDate, meanNdviValue, stressLevel, status, compressedVectorJson, isCached:false, advice}` |
| GET | `/satellite/ndvi?plotId=` | farm owner | history, newest first — **no `advice` on history items** |
| GET | `/satellite/ndvi/latest?plotId=` | farm owner | newest record, or `ndvi: null` |
| GET | `/satellite/ndvi/{id}` | farm owner | one record |

Backend pipeline: validate polygon → Earth Engine `Sentinel-2` → Band 8 (NIR) / Band 4 (RED) →
`NDVI=(NIR−RED)/(NIR+RED)` → classify with the documented thresholds → return compressed vector
alert. Cloud cover > 80 % ⇒ `status:"FAILED"`, `meanNdviValue:null`. NDVI is **not** presented as a
definitive agronomic diagnosis.

#### 2.6.1 The `advice` block

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

```jsonc
// advice (excerpt) — attached to a GENERATED reading
{ "assessment": { "value":0.42, "stressLevel":"MODERATE_STRESS", "label":"Moderate stress",
                  "colour":"warning", "headline":"Watch closely …", "meaning":"…",
                  "band":{"min":0.2,"max":0.45}, "summary":"…" },
  "priority":"SOON",
  "actions":[{"title":"Walk the plot …","detail":"…"}],
  "watchFor":["Thin or patchy emergence"],
  "trend":{"direction":"DECLINING","delta":-0.11,"previousValue":0.53,"previousDate":"2026-06-01","note":"…"},
  "context":{"plotName":"North field","areaHectares":1.13,"observedOn":"2026-06-15",
             "daysSinceObservation":5,"cloudCoverPercent":12,"source":"SENTINEL-2","sourceIsSimulated":false},
  "confidence":{"level":"HIGH","note":"…"},
  "scaleGuide":[{"range":"0.20 – 0.45","meaning":"…"}],
  "limits":["It is an average over the whole plot."],
  "disclaimer":"This is general guidance from a vegetation index, not a site-specific agronomic recommendation." }
```

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
