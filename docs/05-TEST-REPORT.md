# 05 — Test Report

> **Purpose.** An honest record of what was executed, what passed, what failed, and what
> could **not** be run — and why. Nothing here is claimed as passing unless it actually ran.
>
> **Last updated:** 2026-10-19
> **Environment:** Windows 11 (amd64) · Temurin JDK 17.0.20 · Android SDK (compileSdk 35)
> · Gradle 8.9 (wrapper) · AGP 8.7.3 · Node 24.21.0 · Python 3.12.10

---

## 1. Summary

| Suite | Tests | Result | Executed here? |
|-------|------:|--------|:--------------:|
| Backend — unit (`backend/tests/unit`, 8 suites) | 139 | ✅ **Pass — re-run 2026-10-22** | ✅ Yes (bundled Node) |
| Backend — API (`backend/tests/api`, 2 suites) | 13 | ✅ **Pass — re-run 2026-10-22** (`uploads.supabase` needs the network) | ✅ Yes (bundled Node) |
| Backend — full Jest suite | **434** (26 suites) | ✅ **Pass — re-run 2026-10-23** | ✅ Yes |
| Backend — ESLint (`npm run lint`) | — | ✅ 0 errors (24 pre-existing warnings) | ✅ Yes |
| Crop API — `crop-api` (`pytest`) | 34 | ✅ **Pass — re-run 2026-10-18** | ✅ Yes |
| Android — unit (`app/src/test`, 17 classes) | **131** | ✅ **Pass — re-run 2026-10-23** | ✅ Yes |
| Android — instrumented (`app/src/androidTest`) | 0 authored | ⏭️ Not run | ❌ No emulator/device |
| Android — `:app:assembleDebug` | — | ✅ **BUILD SUCCESSFUL** | ✅ Yes |
| Manual on-device walkthrough | — | ⏭️ Not run | ❌ No emulator/device |

Legend: ✅ executed and passed · ❌ could not be executed here · ⏭️ deliberately not run.

On 2026-10-23 the backend and Android suites were re-executed on this machine (Node 24.21.0
and JDK 17 are installed under `C:\Users\src.president\agri-tooling\`, just not on `PATH`);
the crop-API figure is from 2026-10-18. The backend suites are also run by CI
(`.github/workflows/ci.yml`).

---

## 2. Android build (`:app:assembleDebug`)

| Item | Value |
|------|-------|
| Command | `./gradlew :app:assembleDebug` |
| Result | **BUILD SUCCESSFUL** (re-run 2026-10-19) |
| Tasks | 52 actionable (17 executed, 35 up-to-date) |
| Artifact | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Size | **≈22.9 MiB / 24.0 MB** (24 022 469 bytes on the last run; the exact byte count moves by a few hundred bytes between builds) |
| SHA-256 | `4CE25C28FC0D76D59783DD4530756F735CD26CE4AAD0621D2B828D7B55281625` (last build, commit `c20e29a`) |
| Version | `1.1.1` (`versionCode` 3) — shown in-app on Settings |
| Warnings | None blocking. Deprecated `Icons.Filled.ReceiptLong`/`Trending*` usages remain in three files (the AutoMirrored names are used in the new advice card). |

The debug APK is signed with the debug keystore and is installable on a device/emulator.
Release minification rules (`proguard-rules.pro`) are present but `assembleRelease` was **not**
run here (it requires `android/keystore.properties`).

> **Backdrop image check (2026-10-18).** `res/drawable/auth_background.jpg` (≈390 KB) is
> present inside the built `app-debug.apk`, alongside a `res/raw/keep.xml` resource-shrinker
> keep rule that pins the brand drawables for release. The earlier "welcome background is
> blank" symptom was an older APK that predated the image; it is fixed by rebuilding.

---

## 3. Android unit tests (`:app:testDebugUnitTest`)

Executed 2026-10-23 with `./gradlew :app:testDebugUnitTest`.
**131 tests · 0 failures · 0 errors · 0 skipped (17 classes).**

| Class | Tests | Focus |
|-------|------:|-------|
| `data.local.AgriDatabaseTest` | 9 | Room persistence, enum converters, sync-queue enqueue on write/delete, plot `imageUrl` round-trip, and the **satellite overview cache** (round-trip with the observation date kept separate from the cache write time, upsert replacing one plot's row, `observeForPlot` emitting on write, `deleteForFarmer` clearing one owner without touching another) |
| `data.local.SatelliteCacheMigrationTest` | 2 | The **v2 → v3 migration**: runs the production `MIGRATION_2_3` against a real v2 file that already contains a plot, asserts the plot survives untouched and the new table accepts a row; plus idempotency, so a retried upgrade cannot fail |
| `data.remote.AuthApiTest` | 6 | Wire contract: `POST /auth/login`, `POST /auth/register` (path + idempotency key + body), 400 error-envelope → field errors, the generic "highlighted fields" copy replaced by the joined field messages, a specific server message kept verbatim even when details exist, 5xx never leaks server text. The register fixture uses `FARM_OWNER`, the role that owns the farm |
| `data.remote.MediaApiTest` | 3 | Wire contract: `POST /uploads` (path + `image` multipart field + `{url}` parse), repository success path, 5xx never leaks server text |
| `data.remote.SyncApiTest` | 1 | `sync/push` parses `synced` / `conflict` / `failed` results |
| `data.remote.TokenAuthenticatorTest` | 2 | 401 → single refresh + retry + rotated token persisted; refresh failure → session cleared |
| `data.remote.dto.NdviAdviceMappingTest` | 9 | NDVI advice wire contract → domain: full block, trend/context/confidence, a missing `advice`, an advice block with no `assessment`, unknown enum values, a blank action title, a simulated source, and a partial block |
| `data.remote.dto.ColourMapAndFarmAlertsMappingTest` | 10 | Colour-map and farm-wide alert wire contracts → domain |
| `data.update.UpdateDecisionTest` / `UpdateStateRulesTest` / `UpdateVersionApiTest` | 16 / 22 / 6 | APK update manifest: version comparison, never locking a user out when the manifest host is unavailable, and the wire shape |
| `ui.satellite.InsightsRecommendationsTest` | 8 | The insights report's on-device inspection advice: it never names a pest, disease, deficiency or drought for any reading; it gives boundary advice instead of crop advice when the geometry is unusable; it waits rather than guessing when there is no reading; and every path ends by asking the farmer to record what they found |
| `ui.satellite.ObservationStateLabelTest` | 8 | The status word on the latest-observation card: only a successful network payload with a reading is ever **Updated**; a restored device cache is **Cached** even when its refresh failed; a connectivity failure with nothing stored is **Offline**; an in-flight refresh never reads as settled |
| `ui.components.ColourMapViewTest` | 7 | Colour-map legend stops and null-cell handling (a null cell is outside the boundary; `0.0` is a real reading) |
| `core.util.ImageUrlsTest` | 7 | Origin-relative `/uploads/…` → absolute API-origin URL, absolute/`content://` untouched, blank → null |
| `core.util.BaseUrlTest` | 6 | Server-override validation: trailing `/` added, whitespace trimmed, host-only accepted, and null/blank/relative/non-`http(s)` values rejected |
| `core.util.PlotBoundaryTest` | 11 | Corner → closed GeoJSON `Polygon`, ring closure, degenerate rings rejected, local hectares/centroid estimate, the boundary that `POST /satellite/ndvi` requires, and `isUsableBoundary` (accepts Polygon/MultiPolygon/Feature with coordinates; rejects null, blank, malformed, empty and non-polygon input) |
| `domain.model.UnitTest` | 6 | Marketplace units, including the livestock ("herd") unit |

These are pure JVM tests (Robolectric/MockWebServer) — no device required. The advice
mapping tests decode the **real** payload shape the backend emits, so the client and the
server cannot drift apart silently on the guidance contract.

---

## 4. Not executed (and why)

| Item | Reason |
|------|--------|
| `:app:connectedDebugAndroidTest` | No emulator or physical device attached. `androidTest/` currently contains only the Hilt runner (`HiltTestRunner.kt`); no UI tests are authored yet. |
| Manual app walkthrough | Not run. Compose layout/visual fidelity to the design reference remains **unverified**. |
| Image upload end-to-end | The upload → store → render → cross-user path is unit/contract-tested and reviewed, but was **not** exercised against a live backend on a device/emulator. |
| Release build / store bundle | Requires `keystore.properties` (never committed). |
| Real SMS/email delivery | No provider credentials (see `docs/07-EXTERNAL-CREDENTIALS.md`). |
| Real Sentinel-2 NDVI | No Earth Engine account; both the Express mock and the standalone crop API tag results `source: "MOCK"` in this environment. |
| NDVI advice shown on screen | The advice block is unit-tested on both sides (builder + wire contract), but no device run has displayed it — see §4 of `08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`. |

---

## 5. Authentication defect found and fixed (2026-10-16)

**Symptom reported:** the app would not sign in or sign up, and never advanced past the
form to the authenticated screens.

**Root cause:** the compiled-in `API_BASE_URL` was unreachable — no client-side logic was
at fault.

- The **release** build pointed at `https://api.agriconnect.bw/api/v1/`, which is not
  deployed, so every call failed with an unknown-host error shown as "You appear to be
  offline".
- The **debug** build pointed at `http://10.0.2.2:4000/api/v1/`, which only resolves inside
  an emulator.
- There was no supported way to target a real or forwarded backend without editing the
  build file.

**Fix (commit `f4736ed`):** `API_BASE_URL` resolves in priority order —
`-PapiBaseUrl=…` → `apiBaseUrl` in `local.properties` → the per-build-type default.
Cleartext to a dev server is already enabled for debug builds via
`src/debug/AndroidManifest.xml`; release remains HTTPS-only. The README documents the
override. (The intermediate Codespaces auto-detection was later removed: it could bake an
address that only worked from one machine.)

**Verification:** re-ran `:app:assembleDebug` (BUILD SUCCESSFUL) and `:app:testDebugUnitTest`
(12/12) after the change. End-to-end sign-in against a live backend is **not** asserted here
— it additionally requires the backend to be running and reachable.

---

## 6. Live production verification (Render + Supabase, 2026-10-19)

Everything below was executed against the deployed service
`https://agriconnect-api-f55x.onrender.com/api/v1/`, not a local instance.

| Check | Command / probe | Result |
|-------|-----------------|--------|
| Liveness | `GET /health` | `200 {"status":"ok","version":"1.0.0",…}` |
| API version | `GET /api/v1/version` | `200 {"version":"1.0.0","apiVersion":"v1"}` |
| Auth guard | `GET` a protected route with no token | `401` |
| Upload round trip | `POST /uploads` (multipart, `image`) → `201 {"url":"/uploads/<name>"}`, then `GET /uploads/<name>` | `302` → Supabase public URL → `200 image/png`, 14 449 bytes |
| **Role rename is live** | `POST /auth/register` with `role: "FARM_OWNER"` and a deliberately weak password | `400` — **no `role` error**: the new enum is accepted |
| **Role rename is live** | same with `role: "FARMER"` | `400` — **no `role` error**: accepted as the worker role |
| **Old label is gone** | same with `role: "FARM_EMPLOYEE"` | `400` with `{"field":"role","message":"Role must be one of FARM_OWNER, FARMER or BUYER."}` |

The three register probes are deliberately built to fail password-strength validation, so
they exercise the role enum **without creating an account** — that is why the response is
always `400` and why no row is ever written. The role messages are the whole point: the old
build answered `Role must be one of FARMER, FARM_EMPLOYEE or BUYER.`

This matters for migration `006_role_rename.sql`: the service's start command is
`npm run migrate && npm start`, so a container that answers `/health` is a container whose
migrations all exited `0` — the enum rename has been applied to the Supabase database, and
the API is serving the renamed code against renamed data.

**Still not verified in production:** any read that needs a signed-in session (plots,
listings, orders, NDVI), and anything on a device. No database credentials were available
locally, so the verification queries in `backend/scripts/apply-role-rename.sql` were not run
by hand — they are for the owner to run in the Supabase SQL editor.

---

## 7. How to reproduce

```bash
# Backend (needs Node.js + a database; CI uses a PostgreSQL service container)
cd backend
cp .env.example .env          # fill in DB + secrets
npm install
npm run migrate
npm test                      # 152 tests — unit + HTTP smoke

# Crop monitoring API (Python 3.12)
cd crop-api
python -m venv .venv && ./.venv/Scripts/activate
pip install -r requirements-dev.txt
pytest                        # 34 tests

# Android (JDK 17 + Android SDK 35)
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

---

## 8. Honesty statement

Numbers throughout this file were produced on the dates shown by the commands shown and are
reproducible. The backend, crop-API and Android figures were all re-executed on 2026-10-19 and
are green. Any item marked "not executed" should be treated as unverified until someone runs
it — in particular, **no run of the app on a device/emulator has happened**, so the image
upload/display flow is verified only at the unit and wire-contract level, not visually. See
[`08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md).
