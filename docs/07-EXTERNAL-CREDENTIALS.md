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

---

## 2. Google Earth Engine / Sentinel-2 (NDVI)

| Item | Needed |
|------|--------|
| 🟡 GEE access | A Google account registered for Earth Engine (cloud or non-commercial project) |
| 🟡 Service account | `GEE_SERVICE_ACCOUNT_EMAIL` + `GEE_PRIVATE_KEY` (JSON key, never committed) |
| 🟡 Project | `GEE_PROJECT_ID` |
| 🟡 Cost plan | Earth Engine is free for research/non-commercial; commercial use requires a plan |
| 🟡 Asset permission | The service account must be granted access to the project |

**Current behaviour:** `GEE_ENABLED=false`. The pipeline (`POST /satellite/ndvi` → validate polygon →
compute → classify → cache) runs against a **deterministic mock** generator that is always tagged
`source: "MOCK"` in the API response and never presented as real satellite data. The real
implementation (`computeViaEarthEngine` in `src/services/gee.js`) is written and ready but requires
the `@google/earthengine` package and credentials.

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
