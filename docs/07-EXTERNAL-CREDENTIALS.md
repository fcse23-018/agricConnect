# External Credentials and Services To Be Provided

AgriConnect is built so that **every external integration is pluggable and clearly marked as
unconfigured**. Nothing here pretends to work. This file is the shopping list of what the project
owner must supply before production, plus exactly what happens in the meantime.

Legend: 🔴 **required for production** · 🟡 **required for a specific feature** · ⚪ **optional**

---

## 1. Message delivery (password recovery & verification)

| Item | Needed |
|------|--------|
| 🔴 Provider account | Twilio (SMS) and/or an SMTP relay (email) |
| 🔴 Credentials | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` **or** `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS` |
| 🔴 Sender identity | Approved sender ID / from-address (e.g. `no-reply@agriconnect.bw`) |
| 🔴 Cost plan | Per-SMS pricing for Botswana (Botswana networks route via international SMS) |

**Current behaviour:** `DELIVERY_PROVIDER=NONE`. The full password-reset token lifecycle is
implemented (single-use, hashed, 15-minute TTL, rate-limited, always-generic responses), but the
code is written to the `message_outbox` table instead of being sent. With `EXPOSE_DEV_CODES=true`
(non-production only) the API returns the code so the flow is testable end to end.

> **This means password recovery does not reach real users until a provider is configured.** The
> backend refuses to start in production if `DELIVERY_PROVIDER` is `NONE`/`OUTBOX`.

### Twilio (SMS) — implemented

`DELIVERY_PROVIDER=TWILIO` sends through the Twilio REST API (`services/delivery.js`,
`sendViaTwilio`) using HTTPS Basic auth — no `twilio` SDK dependency. Set
`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and `TWILIO_FROM`.

- The returned message SID is stored in `message_outbox.provider_ref`, so a sent message can be
  traced in the Twilio console from its outbox row.
- **A failure is never reported as a delivery.** An incomplete configuration, a non-2xx response
  (Twilio's own error text is kept, e.g. "number is unverified"), a transport error and a 15-second
  timeout all set `delivered=false` and record the reason. `tests/unit/twilioDelivery.test.js`
  pins this: the password-reset flow trusts `delivered`, so a swallowed error would tell a farmer
  their code was sent when nothing left the server.
- **Trial accounts can only message numbers verified in the Twilio console**
  (Phone Numbers → Verified Caller IDs). Sending to an unverified number fails with Twilio error
  21608 and is recorded as a failure. Upgrade the account before real use.

### SMTP (email) — NOT implemented

`DELIVERY_PROVIDER=SMTP` still throws (`sendViaSmtp`), so email is recorded as a failure. Email
delivery needs implementing before it can be enabled.

---

## 2. Google Earth Engine / Sentinel-2 (NDVI)

| Item | Needed |
|------|--------|
| 🟡 GEE access | A Google account registered for Earth Engine (cloud or non-commercial project) |
| 🟡 Service account | A **service-account key**, supplied as `GEE_KEY_FILE` (a mounted JSON file) or `GEE_KEY_JSON` |
| 🟡 Project | `GEE_PROJECT_ID` (read from the key file when it is used) |
| 🟡 Cost plan | Earth Engine is free for research/non-commercial; commercial use requires a plan |
| 🟡 Asset permission | The service account must be granted the **Earth Engine Resource Viewer** role |

> ### ⚠ An API key cannot be used for Earth Engine
>
> Google Maps uses an `AIzaSy…` API key. **Earth Engine does not accept one**, and Google's own
> error says so:
>
> ```json
> { "code": 401,
>   "message": "API keys are not supported by this API. Expected OAuth2 access token or other
>               authentication credentials that assert a principal." }
> ```
>
> It needs a **service-account private key** (a JSON file whose `private_key` is a PEM block).
> A Gmail address such as `someone@gmail.com` is a *user* account and cannot be used by a server —
> it would require an interactive browser sign-in. See §2.1 for how to create the right credential.

**Current behaviour:** Earth Engine is **configured and verified working** on this
deployment (`GEE_ENABLED=true`, a service-account key at `backend/secrets/`). A live run over a plot
near Gaborone returned 10 real observations for 2026-08-26 → 2026-10-20, NDVI 0.1839 → 0.2829,
`source: "SENTINEL-2"`, `isSimulated: false`.

When no credentials are present the pipeline falls back to a **deterministic mock** generator that
is always tagged `source: "MOCK"` / `isSimulated: true` and never presented as real satellite data.

**The real Earth Engine path** (`src/services/gee.js`):

- **Sentinel-2 SR Harmonized** (`COPERNICUS/S2_SR_HARMONIZED`), Band 8 (NIR) / Band 4 (RED) /
  Band 2 (BLUE) at 10 m.
- **Cloud and cloud-shadow masking** via **Cloud Score+** (`GOOGLE/CLOUD_SCORE_PLUS/V1/S2_HARMONIZED`,
  the `cs_cdf` band) — the current, actively maintained cloud product for this collection, rather
  than the deprecated `QA60` band it replaced. A pixel is kept when its score is at or above
  `CLOUD_SCORE_CLEAR_THRESHOLD` (0.60).
  - Linked with `.linkCollection(csPlus, ['cs_cdf'])`. **`linkCollection` matches by band**, so
    joining on `system:index` (a property) fails with
    *"Match image does not have a band named 'system:index'"*.
- **NDVI** `(NIR-RED)/(NIR+RED)` and **EVI** `2.5*(NIR-RED)/(NIR+6RED-7.5BLUE+1)`, averaged over
  the valid in-boundary pixels only.
- **The imagery's own acquisition date** (`system:time_start`), never today's date.
- **Valid-pixel coverage** — the clear pixel count over the boundary's total pixel count, so a low
  percentage is visible rather than hidden.
- A pass above `NDVI_CLOUD_COVER_FAIL_THRESHOLD` (default 80%) **fails with a dated reason**.

> ### ⚠ A fully-masked pass is never reported as NDVI = 0
>
> `reduceRegion` returns **0** for a mean over an empty set. On a 100%-cloud pass every pixel is
> masked out, and recording that 0 as an observation would read as "bare soil / dead crop" when the
> truth is that nothing was observed — a fabricated value, and one that would corrupt the growth
> analysis and raise false stress alerts. (This was a real defect: five of fifteen live readings
> were affected before it was fixed.)
>
> Usability is therefore decided by the valid **pixel count**, not the mean:
> - a series pass with zero valid pixels is **dropped** from the series;
> - a single observation with zero valid pixels returns `FAILED` —
>   *"Every pixel inside this boundary was cloud or cloud shadow in the latest pass."*
>
> `tests/unit/gee.test.js` pins this in both paths.

The Node API can now produce a real series **without** the FastAPI `crop-api/` service: with
`GEE_ENABLED=true`, `fetchObservations` uses Earth Engine directly (`fetchGeeSeries`) after
crop-api and before the mock.

**Tests:** `tests/unit/gee.test.js` (24) and `tests/unit/geeCredentials.test.js` (11).

### 2.1 Creating the service-account key

1. **Enable the API** — [Earth Engine](https://console.cloud.google.com/apis/library/earthengine.googleapis.com)
   → **Enable** on project `agriconnect-512210`.
2. **Register the project for Earth Engine** at
   [code.earthengine.google.com/register](https://code.earthengine.google.com/register). A
   non-commercial/research registration is free; pick the same Cloud project.
3. **Create a service account** — IAM & Admin → Service Accounts → **Create**. Name it e.g.
   `agri-satellite`.
4. **Grant it a role** — add the role **Earth Engine Resource Viewer**
   (`roles/earthengine.viewer`) on the project.
5. **Create its key** — open the service account → Keys → **Add key → Create new key → JSON**.
   A `.json` file downloads. **This file is a live secret**: it cannot be rotated by a console
   toggle, and anyone holding it can read your Earth Engine assets.
6. **Supply it to the server**, in your preferred shape:

   | Where | How |
   |-------|-----|
   | Local dev | `GEE_KEY_FILE=C:\path\to\key.json` in `backend/.env` |
   | Render (recommended) | Upload as a **Secret File**, then set `GEE_KEY_FILE=/etc/secrets/<name>.json` |
   | Any host without file mounts | `GEE_KEY_JSON={"type":"service_account",...}` (store it as a secret, not a plain env var) |

7. **Switch it on** — set `GEE_ENABLED=true`, then redeploy.

If `GEE_ENABLED=true` but the key fails to load, the backend **refuses to start in production**
rather than silently serving mock data that looks real.

---

## 3. Map tiles / mapping SDK

**Resolved:** Google Maps Platform (Maps SDK for Android) — see
[`docs/14-GOOGLE-MAPS.md`](14-GOOGLE-MAPS.md).

| Item | Needed |
|------|--------|
| ✅ Decision | **Google Maps SDK for Android** (was an OSMDroid placeholder; osmdroid was declared but never used and has been removed) |
| 🟡 Android key | Maps key in the git-ignored `android/local.properties` (`mapsApiKey=...`), restricted in Cloud Console to package + signing SHA-1 |
| 🟡 Google Weather (optional) | `GOOGLE_WEATHER_API_KEY`, **server-side only**, restricted by API + IP. Optional: Open-Meteo is the default and needs no key |
| 🟡 Billing | Google Maps Platform billing must be enabled; Maps SDK for Android has a free monthly tier |
| ⚪ Places / Solar | Enabled on the current key but **not used by the app**; narrow the key's API restrictions if they stay unused |

**Security:** an Android key inside an APK is extractable, so it is restricted rather than
hidden (package name + SHA-1 for both `bw.agriconnect` and `bw.agriconnect.debug`). The Weather
key is a bearer credential and stays server-side; it is never sent to a device or written to a
log or API response. Rotate a key immediately if it is ever exposed —
`git grep "AIzaSy"` must return nothing.

---

## 4. Hosting, TLS and domain

| Item | Needed |
|------|--------|
| 🔴 Host | VM / container host for the Node service + PostgreSQL 15+ |
| 🔴 TLS certificate | Valid cert for `api.agriconnect.bw` (Let's Encrypt acceptable) |
| 🔴 Domain | DNS for API + the public account-deletion page |
| 🟡 Object storage | Supabase Storage bucket `agriconnect-uploads` (or serve locally at small scale) — see §4.1 |
| ⚪ Monitoring | Uptime + error monitoring (Sentry or equivalent) |

### 4.1 Supabase Storage bucket — one-time SQL

Image uploads need a bucket to exist before they can work. `UPLOAD_DRIVER=supabase` is
required on a free host, because the free filesystem is ephemeral and anything written to
disk is destroyed on every redeploy and every spindown. With no bucket, Supabase answers
every upload with `NoSuchBucket` (HTTP 400), `POST /uploads` returns a generic 500, and
the app shows its image placeholder — which is what "uploaded photos render blank"
actually is.

The API creates the bucket itself on first upload, but only if
`SUPABASE_SERVICE_ROLE_KEY` is the true `service_role` secret. To remove that dependency,
run [`backend/scripts/create-storage-bucket.sql`](../backend/scripts/create-storage-bucket.sql)
once in the Supabase SQL editor (Dashboard → SQL Editor → New query). It is idempotent and
sets `public = true`, a 2 MB limit and the three accepted MIME types.

Verify the bucket, the migrations, and how much data still lacks an NDVI boundary:

```sql
-- Bucket exists, is public, and carries the documented limits.
select id, name, public, file_size_limit, allowed_mime_types
from storage.buckets
where id = 'agriconnect-uploads';

-- Objects that have actually reached storage.
select count(*) from storage.objects where bucket_id = 'agriconnect-uploads';

-- Migrations the API has applied (the runner is backend/src/db/migrate.js).
select filename, applied_at from schema_migrations order by filename;

-- Plots with no NDVI boundary — these cannot produce an NDVI reading.
select count(*) filter (where geojson_boundary is null)                    as without_boundary,
       count(*) filter (where jsonb_typeof(geojson_boundary) <> 'object')  as malformed,
       count(*)                                                            as plots
from farm_plots
where deleted_at is null;
```

`farm_plots.geojson_boundary` already exists (`migrations/002_domain.sql`, jsonb) and is
written by the sync engine, so **no schema change is needed** for the boundary. To exercise
NDVI end to end before walking a field — or before installing the build that can draw a
boundary — a square can be seeded directly:

```sql
update farm_plots
set geojson_boundary = jsonb_build_object(
      'type', 'Polygon',
      'coordinates', jsonb_build_array(jsonb_build_array(
        jsonb_build_array(25.9100, -24.6500),
        jsonb_build_array(25.9110, -24.6500),
        jsonb_build_array(25.9110, -24.6490),
        jsonb_build_array(25.9100, -24.6490),
        jsonb_build_array(25.9100, -24.6500)   -- GeoJSON rings must close
      ))
    ),
    area_hectares = 1.13,
    centroid_lat  = -24.6495,
    centroid_lng  = 25.9105
where id = '<plot-uuid>';                       -- replace with a real plot id
```

---

## 5. Google Play publishing

| Item | Needed |
|------|--------|
| 🔴 Developer account | Google Play Developer account (one-time fee) |
| 🔴 Signing keystore | `android/keystore.properties` + keystore file (see store checklist) |
| 🔴 Store listing | Screenshots, feature graphic, description, content rating |
| 🔴 Privacy policy URL | Hosted `docs/privacy-policy.md` |
| 🔴 Data-safety form | Must match actual collection (see store checklist) |
| 🔴 Account-deletion URL | Hosted `docs/account-deletion.html` |

---

## 6. Apple App Store

**Not applicable in this build.** The application is native Kotlin/Android only. A Kotlin Android
app cannot be submitted to the App Store; an iOS client would be a separate project. The brief made
iOS conditional on it actually being developed (conflict C6).

---

## 7. Legal / compliance input

| Item | Needed |
|------|--------|
| 🔴 Privacy policy review | Counsel sign-off under the Botswana Data Protection Act, 2024 |
| 🔴 ToS review | Counsel sign-off |
| 🔴 Retention confirmation | Confirm 84-month financial retention (U5) |
| ⚪ Registration | Data-controller registration if required |

---

## 8. Optional / future

| Item | Needed |
|------|--------|
| ⚪ Push notifications | Firebase project + `google-services.json` (in-app feed works today; push is not wired) |
| ⚪ Payments | Explicitly out of scope until separately approved — orders carry a total but no payment flow exists |
| ⚪ Setswana localisation | Translator + Phase-3 scheduling |
| ⚪ Crash reporting | Account + DSN (disabled by default to avoid sending PII) |

---

## Summary — what blocks a production launch

1. SMS/email provider credentials (password recovery is non-functional without them).
2. Hosting + TLS + domain.
3. Legal review of the privacy policy, ToS and retention periods.
4. Play signing keystore + store listing assets.
5. Earth Engine service account (otherwise NDVI stays in clearly-labelled mock mode).
