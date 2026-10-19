# Known Limitations and Unverified Items

This file exists so that nothing in this repository overstates what has been demonstrated. Where a
feature could not be executed, it is listed here as **unverified** rather than reported as working.

---

## 1. Verification status (read this first)

The build machine started with **no toolchains installed** (no Java, Gradle, Android SDK, Node,
PostgreSQL). A portable, admin-free toolchain was installed during the build. As of
**2026-10-18** the backend (`node`), Android (`JDK 17` + SDK 35) and crop-API (`Python 3.12`)
suites have all been executed on this machine — see `05-TEST-REPORT.md`. Only device/emulator
and provider-dependent items remain unexecuted.

| Area | Status |
|------|--------|
| Backend code compiles / runs | ✅ Executed — see `05-TEST-REPORT.md` |
| Android app compiles | ✅ Executed (`:app:assembleDebug`, 22.5 MB APK — `1.1.0`/build 2) |
| Backend tests executed | ✅ 152/152 — see `05-TEST-REPORT.md` |
| Crop-API tests executed | ✅ 34/34 (`pytest`, mock GEE) |
| Android unit tests executed | ✅ 28/28 — see `05-TEST-REPORT.md` |
| Instrumented (device) tests executed | **Not executed** — requires an emulator/device |
| Image upload/display on a device | **Not verified** — unit/wire-tested only, never run on a device |
| Server override (Settings → Server) | **Not verified on a device** — the stored value and restart path are reviewed, but the URL rewrite was never exercised against a live host |
| Real SMS/email delivery | **Not possible** — no provider configured |
| Real Sentinel-2 NDVI | **Not possible** — no Earth Engine account; mock mode is clearly labelled |

---

## 2. Functional limitations (deliberate, per the brief)

| # | Limitation | Why |
|---|-----------|-----|
| L1 | **No online payments.** Orders carry a computed total but there is no payment step. | Brief §13 forbids payment processing without separate approval. |
| L2 | **No biometric login / facial recognition.** | Architecture v1.1 removes biometrics entirely. |
| L3 | **No PIR motion camera.** | Excluded by v1.1 (conflict C2); the draft plan's `MotionLogger.kt` was **not** built. |
| L4 | **No iOS app.** | Native Kotlin/Android only (conflict C6). |
| L5 | **Password recovery is not production-ready.** | Token lifecycle is complete and tested, but no delivery provider is configured. |
| L6 | **Push notifications are not wired.** The in-app notification feed works. | No Firebase project supplied. |
| L7 | **NDVI is mock unless Earth Engine is configured.** Mock results are tagged `source: "MOCK"`. | No GEE credentials. |
| L8 | **Setswana localisation is not included.** | Documented as a Phase-3 item. |
| L9 | **The farmer role requires a linked farm.** A farmer (the account that works the land) with no employer cannot record field data; the farm owner who employs them maps the plots. | Inferred gap fix (migration `005_employee_link`, role renamed by `006_role_rename`); the employer link is added by phone number. |
| L10 | **Buyer order confirmation wording.** The v1.1 permission matrix lists both Farmer and Buyer under "Manage Orders & Confirm Deliveries". | The build follows the narrative: **farmer confirms**; a buyer may only cancel their own `PENDING` order (conflict C4). Needs owner confirmation. |

---

## 3. Requirements that need owner confirmation (carried from `docs/00`)

- U1 SMS/email provider + credentials for password recovery.
- U2 Earth Engine service account / project.
- U3 Map provider sign-off (OSMDroid is used by default).
- U4 Legal review of the privacy policy and ToS.
- U5 Confirmation of the 84-month financial retention period.
- U6 The SRS (FR/NFR numbering) to finalise the traceability matrix — the SRS was **not** among the
  supplied documents.
- U7 Whether email verification is mandatory when an email is supplied.
- U8 Setswana localisation timing.
- U9 Release signing keystore ownership.
- U10 Production hosting + TLS.

---

## 4. Technical debt / next steps

| # | Item | Note |
|---|------|------|
| T1 | Scheduled deletion processing | `processDueDeletions()` exists but must be scheduled (cron/queue) in production. |
| T2 | Email verification enforcement | Codes are issued; the account is usable before verification. Decide whether to gate features. |
| T3 | ~~Listing image upload~~ | **Done 2026-10-18**: listings, farm plots and avatars now upload through `POST /uploads` and render via Coil (`MediaRepository`, `AgriRemoteImage`). **Avatar 404 fixed 2026-10-19** (double-hyphen stored name). Device smoke test still outstanding. |
| T3b | Wire the crop-API into the app | `crop-api/` is a standalone service; the app still reads satellite data through the Express `/satellite` mock. Aligning the app with the new service is future work. |
| T4 | Offline map tiles | OSMDroid can cache tiles; explicit pre-download of a Botswana region is not implemented. |
| T5 | Conflict UI | Conflicts surface as a `CONFLICT` badge with a review affordance; a full merge screen is minimal. |
| T6 | Rate-limit storage | In-memory rate limiting is per-instance; a multi-instance deployment needs a shared store (a managed cache or the database), not the process heap. |
| T7 | Idempotency key retention | The `idempotency_keys` table grows; add a purge job. |
| T8 | NDVI advice on a device | The post-reading guidance is unit-tested on both sides (builder, wire contract and mapping) but has not been displayed on a device — no emulator/phone was available. |
| T9 | Role rename deployment | `006_role_rename.sql` runs automatically at deploy (`npm run migrate`). **Verified live 2026-10-19**: `POST /auth/register` accepts `FARM_OWNER` and `FARMER` and rejects `FARM_EMPLOYEE`, and the container only serves once its migrations exited `0` — see `05-TEST-REPORT.md` §6. `backend/scripts/apply-role-rename.sql` applies the same change idempotently by hand if a database is ever found on the old labels. |

---

## 5. What was NOT verified and must not be claimed

- That the release App Bundle installs and runs on a physical device.
- That SMS/email actually arrives (no provider).
- That Sentinel-2 NDVI values are accurate (no Earth Engine).
- That the app passes Google Play review.
- That the retention periods satisfy Botswana law (needs counsel).
- That instrumented UI tests pass (no device was available).

Honest reporting is a hard requirement of this project: **nothing above is marked as passing until
it has actually been run and observed.**
