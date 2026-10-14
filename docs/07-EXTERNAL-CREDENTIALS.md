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

| Item | Needed |
|------|--------|
| ⚪ Decision | Confirm **OSMDroid (OpenStreetMap)** or supply a key-based provider |
| 🟡 If Google/Mapbox | API key + billing |

**Current:** OSMDroid is used — free, no API key, supports offline tile caching, appropriate for a
Botswana offline-first pilot. It is isolated behind a map abstraction so it can be swapped.
**Requires product-owner sign-off** (recorded as U3).

---

## 4. Hosting, TLS and domain

| Item | Needed |
|------|--------|
| 🔴 Host | VM / container host for the Node service + PostgreSQL 15+ |
| 🔴 TLS certificate | Valid cert for `api.agriconnect.bw` (Let's Encrypt acceptable) |
| 🔴 Domain | DNS for API + the public account-deletion page |
| 🟡 Object storage | S3-compatible bucket for avatars/listing images (or serve locally at small scale) |
| ⚪ Monitoring | Uptime + error monitoring (Sentry or equivalent) |

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
