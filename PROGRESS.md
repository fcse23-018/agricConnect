# AgriConnect — Implementation Status & Handover

> **Purpose.** A single, honest map of what exists in this repository, what is
> stubbed or mocked, and what is still missing — so a developer joining the project
> can orient in minutes and know exactly where to start.
>
> **Last updated:** 2026-10-19 · **Baseline commit:** `005bdbf` (+ uncommitted work
> described below) · **Platform:** Windows, JDK 17, Node 24, Python 3.12, PostgreSQL 17
>
> **How to read the status markers**
> ✅ **Done** — implemented and actually executed/verified in this environment ·
> 🟡 **Partial / unverified** — code exists but is incomplete, mocked, or was not
> executed · ❌ **Missing** — not present.
>
> Nothing here should be read as a claim that something *works on a device*. The
> Android app has been **compiled and unit-tested**, but has **not been run on an
> emulator or physical device** in this environment. See §7 and §9.

---

## 0. TL;DR for a new contributor

| Area | State |
|------|-------|
| **Backend REST API** | 🟢 Every endpoint in the contract is implemented |
| **Backend tests** | 🟢 152/152 pass — 139 unit + 13 API (re-run 2026-10-19) |
| **Crop-monitoring API** | 🟢 `crop-api/` FastAPI service — 34/34 pytest pass (mock GEE by default) |
| **Backend ↔ Supabase schema** | 🟢 `backend/supabase-update.sql` verified against PostgreSQL 17 (§6) |
| **Live deployment** | 🟢 Render + Supabase: `/health` 200, `/version` 200, protected routes 401, upload round trip `201 → 302 → 200`, and the role rename confirmed on the live API (`05-TEST-REPORT.md` §6) |
| **Android data + domain layer** | 🟢 Room (v2), Retrofit, sync engine, session, 12 repositories |
| **Android app shell** | 🟢 `AgriConnectApp`, `MainActivity`, Hilt modules, nav host all present |
| **Android UI** | 🟢 26 Compose screens, role-based navigation, offline/sync affordances |
| **Image upload / display** | 🟢 Listings, plots and avatars: pick → `POST /uploads` → store → render (unit/contract-tested; not yet device-verified) |
| **Plot boundary (NDVI input)** | 🟢 Add/edit plot captures the polygon (GPS corners or manual lat/lng); stored in `farm_plots.geojson_boundary`, pushed via `/sync/push`, and required by `POST /satellite/ndvi` |
| **Android build** | 🟢 `:app:assembleDebug` produces a 24.2 MB APK — `1.1.1` (build 3), re-run 2026-10-20 |
| **Backend tests** | 🟢 372/372 pass, 23 suites, 0 failures (re-run 2026-10-21) |
| **Android unit tests** | 🟢 114/114 pass, 15 suites, 0 failures (`:app:testDebugUnitTest`, re-run 2026-10-21) |
| **Release distribution** | 🟢 CI uploads a debug APK named `agriconnect-debug-v<version>-<sha>.apk` (§8) |
| **Android runtime** | 🟡 Never launched on a device/emulator — no instrumented test run |
| **External providers** | 🔴 SMS/email, Earth Engine and push are not configured |
| **Docs / deliverables** | 🟢 All referenced docs present — adds `docs/11-SYSTEM-INVENTORY-AND-STATUS.md` (Phase 1) |

---

## 1. Repository layout

```
agriConnect/
├─ android/    Native Android app (Kotlin · Jetpack Compose · Room · Hilt · WorkManager)
├─ backend/    Node.js/Express REST API + PostgreSQL migrations + Supabase update script
├─ crop-api/   Standalone FastAPI crop-monitoring service (Google Earth Engine · Sentinel-2)
├─ docs/       Written deliverables (requirements, design, API contract, …)
├─ .github/    CI workflow
├─ README.md   Project overview + deliverable index
├─ CONTRIBUTING.md
├─ LICENSE     MIT
└─ PROGRESS.md ← this file
```

---

## 2. Backend (`backend/`) — Node.js / Express / PostgreSQL

**Stack:** native ESM (`"type": "module"`), Express 4, PostgreSQL (`pg`), Zod validation,
JWT auth, Helmet/CORS/compression, Swagger UI, Jest.

### 2.1 What is done ✅

| Component | Location | Notes |
|-----------|----------|-------|
| App bootstrap, security headers, CORS, request IDs | `src/app.js` | Versioned at `/api/v1`; health at `/health` |
| Config (env-driven) + production secret assertions | `src/config/index.js` | Refuses to boot in prod with dev secrets |
| DB pool, transactions, healthcheck | `src/db/index.js` | |
| Migrations (6, ordered) + runner + seed | `backend/migrations/`, `src/db/migrate.js`, `src/db/seed.js` | `001_init` … `006_role_rename` |
| Auth middleware (JWT bearer) + role guard (RBAC, 3 roles) | `src/middleware/auth.js`, `roles.js` | UI is never the enforcement point |
| Validation + error envelope + rate limiting + idempotency | `src/middleware/` | Never leaks stack traces |
| Auth module (register→login→refresh→reset→verify) | `src/modules/auth/` | Full token lifecycle |
| Profile, avatar, export, employee-link, deletion lifecycle | `src/modules/users/`, `account/`, `public/` | Re-auth, grace period, retention ledger |
| Farms/plots, harvests, livestock | `src/modules/farms/`, `farm/` | GeoJSON boundaries; plots carry an optional `imageUrl` photo |
| Image uploads (generic + avatar) | `src/modules/uploads/`, `src/services/uploads.js` | `POST /uploads` (field `image`, ≤2 MB jpeg/png/webp) and `POST /users/me/avatar`; files stored under `backend/uploads/`, served from the origin at `/uploads/…` |
| Sales, expenses, financial summary | `src/modules/finance/` | Farmer-only |
| Satellite NDVI (mock + real GEE path) | `src/modules/satellite/`, `services/gee.js`, `services/ndvi.js` | NDVI arithmetic unit-tested |
| Marketplace listings + orders workflow | `src/modules/marketplace/`, `orders/` | Transactional stock, no payments |
| Notifications feed + sync push/pull + audit log | `src/modules/notifications/`, `sync/`, `services/audit.js` | In-app only |
| Tests | `tests/unit/*` (8 files), `tests/api/{smoke,uploads.supabase}.test.js` | **152 tests, all passing** |
| OpenAPI 3 spec, Dockerfile, docker-compose | `backend/openapi.yaml`, `Dockerfile`, `docker-compose.yml` | |
| ESLint config, lockfile | `eslint.config.js`, `package-lock.json` | |
| Supabase schema update script | `backend/supabase-update.sql` | Idempotent; **verified** — see §6 |

### 2.2 What is partial / mocked 🟡

| Item | File | Reality |
|------|------|---------|
| Email/SMS delivery | `src/services/delivery.js` | `SMTP`/`Twilio` **throw "not configured"**. Only `NONE`/`OUTBOX` work (writes to `message_outbox`). Codes are never actually sent. |
| Earth Engine / Sentinel-2 | `src/services/gee.js` | Real GEE path written but **unverified**; default is a deterministic mock (`GEE_ENABLED=false`), tagged with `source`. |
| Push notifications | `src/modules/notifications/` | In-app feed works; **no FCM/APNs**. `device_tokens` are stored but unused. |
| Database-backed integration tests | `tests/api/` | Only an HTTP smoke suite (health/version/404/401/request-id) — **no test exercises real rows** (auth lifecycle, sync, orders). |

### 2.3 What is missing ❌

- Real provider credentials — see [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md).
- End-to-end tests against a live PostgreSQL (the runner supports them; the suite does not exist).

---

## 2·b Crop-monitoring API (`crop-api/`) — FastAPI / Google Earth Engine

A **standalone** Python service that sits *alongside* the Node backend (chosen so the
Express API keeps serving app data unchanged). It is the messenger between the mobile app
and Google Earth Engine + Sentinel-2: it turns a bounding box / centre-and-size into an area,
NDVI-family indices and a per-plot health summary. Documented in
[`docs/10-CROP-MONITORING-API.md`](docs/10-CROP-MONITORING-API.md).

**Endpoints:** `GET /health`, `POST /v1/area`, `POST /v1/indices`, `POST /v1/health-summary`;
optional `X-API-Key` when `API_KEY` is set.

| Item | State | Notes |
|------|-------|-------|
| Service, schemas, geometry, summary, caching | ✅ | `crop-api/app/` (`main`, `schemas`, `geometry`, `gee`, `cache`, `security`, `config`) |
| Tests | ✅ | **34/34 pytest pass** (re-run 2026-10-18) — `tests/` |
| Earth Engine path | 🟡 | Real GEE code written, but **not** enabled/verified here; default `GEE_ENABLED=false` returns deterministic synthetic values tagged `"source": "mock"` |
| Packaging | ✅ | `requirements*.txt`, `pyproject.toml`, `Dockerfile`, `docker-compose.yml`, `.env.example`, `README.md`, `scripts/ndvi_plot.py` CLI |

It is **not** wired into the Android app or the Node backend yet — the app still reads
satellite data through the existing Express `/satellite` mock. Making the app call this
service (base-URL config + DTO alignment) is future work.

---

## 3. Android (`android/`) — Kotlin / Compose / Hilt / Room

**Stack:** Kotlin 2.0.21, AGP 8.7.3, Jetpack Compose (Material 3), Room, Hilt,
WorkManager, Retrofit + kotlinx.serialization, Coil, osmdroid. JDK 17,
`compileSdk` 35, `minSdk` 24. Charts are hand-rolled (`AgriBarChart`) — **no Vico**.

### 3.1 What is done ✅

| Layer | Location | Notes |
|-------|----------|-------|
| App entry points | `AgriConnectApp.kt` (`@HiltAndroidApp`), `MainActivity.kt` (`@AndroidEntryPoint`) | |
| DI | `di/AppModule.kt`, `DatabaseModule.kt`, `NetworkModule.kt`, `RefreshClient.kt` | Provides Room, Retrofit, `ApiService`, session, sync, repositories |
| Build config | `android/build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` | Per-buildType `API_BASE_URL`; `proguard-rules.pro` present |
| Manifest, resources, splash, backup rules | `app/src/main/AndroidManifest.xml`, `res/` | |
| Domain models | `domain/model/{User,Enums,FarmModels}.kt` | `Session`, `UserRole`, `OrderStatus`, `ListingStatus`, `Unit.label` |
| Local DB (Room) | `data/local/` | Entities + DAOs for plots, harvests, livestock, sales, expenses, listings, orders, notifications, NDVI, sync queue |
| Networking | `data/remote/` | `ApiService` declares the full REST surface; `AuthInterceptor`, `TokenAuthenticator` (silent refresh, no recursion), `NetworkErrorParser` |
| DTO envelopes | `data/remote/dto/` | `MeResponse`, `NdviResponse`, `DeletionRequestResponse`, paginated `{items,…}` — the old contract mismatches are fixed |
| Session / tokens | `data/session/` | Encrypted token storage |
| Sync engine | `data/sync/` | `SyncEngine`, `SyncQueue`, `SyncApplier`, `ConflictResolver`, `SyncScheduler`, `SyncWorker` (WorkManager) |
| Repositories | `data/repository/` (12) | Auth, FarmPlot, Harvest, Livestock, Finance, Marketplace, Order, Notification, Satellite, Account, **Media** (generic image upload) |
| Image pipeline | `core/util/ImageUrls.kt`, `ImageFiles.kt`, `data/repository/MediaRepository.kt`, `ui/components/AgriRemoteImage.kt` | Pick → `POST /uploads` → store the origin-relative path on the record → Coil renders it after resolving against the API origin (also shows images uploaded by other users) |
| Plot boundary mapping | `core/util/PlotBoundary.kt`, `core/util/CurrentLocation.kt`, `ui/components/BoundaryEditor.kt` | Corners from GPS or manual lat/lng, serialised to a closed GeoJSON Polygon with a local area/centroid estimate; the input NDVI cannot be computed without. Pure geometry is unit-tested |
| Server endpoint | `core/config/ServerConfig.kt`, `core/util/BaseUrl.kt`, `ui/settings/ServerSettingsScreen.kt` | Defaults to the URL compiled into the build type; a **debug** build can persist an override (Settings → Server) and restarts to apply it, so an installed APK can point at a Codespaces forward or a LAN host with no rebuild |
| Result type | `core/result/AppResult.kt` | `AppResult.Success/Failure`, `AppError.message/isNetwork` |
| Design system | `ui/theme/` | Tokens match [`docs/01-DESIGN-SYSTEM.md`](docs/01-DESIGN-SYSTEM.md) |
| Reusable components | `ui/components/` (16 files) | Scaffold, surfaces, buttons, dialogs, state views, chips, segmented control, chart, badges, settings rows, logo, remote image, boundary editor |
| Navigation | `ui/AgriConnectRoot.kt`, `ui/navigation/` | Root `NavHost`, `MainScaffold` chrome, `AgriNavItems.forRole`, `homeRouteFor` |
| Screens | `ui/**` (27 `*Screen.kt`) | See §3.2 |
| Unit tests | `app/src/test/...` (9 files) | **50/50 passing** |
| Instrumented test scaffold | `app/src/androidTest/.../HiltTestRunner.kt` | Runner exists; no test classes yet |

### 3.2 Implemented screens ✅

Splash · Welcome · Onboarding · Login · Register · Forgot password · Dashboard ·
Farm (plots) · Farm detail · Harvests · Livestock · Satellite insights · Finance ·
Marketplace · Listing detail · Create listing · Orders · Order detail ·
Notifications · Profile · Settings · Notification preferences · Privacy & consent ·
Account deletion · Offline & sync · Help · Server (debug builds only).

The **Satellite** tab is a real destination: it resolves the owner's plots from the
local cache, defaults to the first one, and offers a chip row to switch plots when
there is more than one. An empty plot list shows an "Add a plot" state that routes to
the Farm tab. A pushed `satellite/{plotId}` route opens the same screen with a back
arrow and a fixed plot.

Role-based bottom navigation (no tab a role cannot open):
**FARM_OWNER** home/farms/satellite/market/more ·
**FARMER** home/farms/market/orders/more · **BUYER** market/orders/alerts/more.
`more` is in every role because it is the only route to profile, settings and sign-out.
**Every role can buy** — the marketplace is open to all three, so no role has a dead
"buy" affordance. Orders and finance stay reachable from the dashboard shortcuts and the
top-bar notification bell without needing a tab of their own.

### 3.3 Behaviour that is deliberately honest 🟡

- **Offline-first writes** (plots, harvests, livestock, sales, expenses) go to Room and
  queue; reads fall back to cached rows flagged `isFromCache`.
- **Listings and orders are server-authoritative** — create/publish/archive/update and
  every order transition go over REST and are **not** queued. Orders are never mutated
  optimistically; the UI re-reads the server's state. The domain enum is only
  `PENDING, CONFIRMED, COMPLETED, CANCELLED` (a rejected order becomes `CANCELLED`).
- **Satellite NDVI is never fabricated** on the client; an unavailable/unfabricated
  result shows an honest empty state, and `cloudCover` is shown verbatim (its scale is
  not documented by the backend).
- **The NDVI reading comes with advice, and says what it cannot know.** What the band
  means, what to check in the field, how far the *measurement* can be trusted, the
  published scale it sits on, and the limits of a vegetation index. A simulated (`MOCK`)
  reading is labelled as demonstration data in the response and on screen, and the
  guidance never asserts a cause for a low value.
- **Sign out** always clears the local session, even when the server call fails (§8).
- Notification taps route only on a **known** `relatedEntityType`; anything unknown is
  ignored rather than guessed.

### 3.4 Fixed / added in the latest pass ✅

| Change | File(s) |
|--------|---------|
| Legal consents (`acceptTerms`, `acceptPrivacy`) were dropped by kotlinx.serialization defaults → `@EncodeDefault(ALWAYS)` | `data/remote/dto/AuthDtos.kt` |
| Profile / Settings "Sign out" navigated but **never ended the session** → VMs now call `AuthRepository.logout()` | `ui/profile/ProfileViewModel.kt`, `ui/settings/SettingsViewModel.kt`, matching screens |
| `AuthRepository.logout/logoutAll` only cleared the session on some error codes → local session is always cleared | `data/repository/AuthRepository.kt` |
| Welcome/auth backdrop rendered blank → solid brand colour behind the photo, lighter scrim, white hero text; `res/raw/keep.xml` pins brand drawables for release. **`auth_background.jpg` now confirmed inside the built APK.** | `ui/components/AuthScaffold.kt`, `ui/welcome/WelcomeScreen.kt`, `ui/components/BrandLogo.kt`, `res/raw/keep.xml` |
| **New image support** end-to-end (listing, plot, avatar): pick → upload → store → render, including other users' images | `core/util/ImageUrls.kt`, `core/util/ImageFiles.kt`, `data/repository/MediaRepository.kt`, `ui/components/AgriRemoteImage.kt`, `ui/{marketplace,farms,profile}/…` |
| Room bumped to **v2** with a real `MIGRATION_1_2` adding `farm_plots.image_url` (no destructive fallback) | `data/local/Migrations.kt`, `AgriDatabase.kt`, `DatabaseProvider.kt`, `data/local/entity/FarmPlotEntity.kt` |
| **Avatar/profile upload 404'd** because the stored name had a doubled hyphen (`avatar--<uuid>.png`) while the `/uploads/:name` pattern only matched one → one shared `storedNameFor()` + `STORED_NAME_PATTERN` (accepting the legacy double hyphen) used by both the writer and the reader | `src/services/uploads.js`, `src/app.js`, `src/modules/users/users.routes.js`, `tests/unit/uploads.test.js` |
| **Roles renamed so they describe who a person is**: old `FARMER` (owns the farm) → `FARM_OWNER`, old `FARM_EMPLOYEE` (works the land) → `FARMER`, `BUYER` unchanged. In-place `ALTER TYPE … RENAME VALUE`, so existing accounts keep their capabilities; **all three roles can buy** | `migrations/006_role_rename.sql`, `src/middleware/roles.js`, finance/farms/farm-records/satellite/marketplace/orders routes, `src/services/{orders,records,auth}.service.js`, `src/sync/*`, `src/db/seed.js`, `tests/unit/roles.test.js` |
| **Landing route is now role-appropriate** and no role has a tab it cannot open; `more` is in every role because it is the only route to profile/settings/sign-out | `ui/navigation/AgriNavItems.kt`, `domain/model/Enums.kt`, `ui/AgriConnectRoot.kt`, `ui/dashboard/DashboardScreen.kt` |
| **Sign-up and sign-in had no way back to the main screen** → shared translucent circular back arrow on both, pinned top-start inside the safe drawing area | `ui/components/AuthScaffold.kt`, `ui/auth/LoginScreen.kt`, `ui/auth/RegisterScreen.kt`, `ui/AgriConnectRoot.kt` |
| **Guidance after the vegetation index is computed**: what the band means, what to check in the field, the trend against the previous reading, the area averaged and the observation's age, how far the measurement can be trusted, the published scale, and what a vegetation index cannot say. Built once server-side and carried by the response; never invented, never a stated cause | `src/services/ndviAdvice.js`, `src/modules/satellite/satellite.{service,routes}.js`, `ui/components/NdviAdviceCard.kt`, `ui/satellite/Satellite{Screen,ViewModel}.kt`, `data/remote/dto/{SatelliteDtos,Mappers}.kt`, `domain/model/FarmModels.kt` |
| **"Everything visible" pass**: every main screen already scrolled or was a `Lazy*` list; the three short screens that did not (Help & support, Offline & sync, Notification preferences) now scroll, and bottom-bar labels wrap to two lines with an ellipsis fallback instead of being clipped on a narrow phone | `ui/settings/{Help,OfflineSync,NotificationPrefs}Screen.kt`, `ui/components/AgriScaffold.kt` |

### 3.5 Not verified ❌

- The app has **not been launched**. No emulator/device smoke test, no screenshot, no
  instrumented test. Compose layout/visual fidelity to the design reference is
  therefore **unverified** — only the code paths were reviewed and compiled.
- The **image flow has not been exercised on a device against a live backend**: the picker,
  the multipart upload, the round-trip of `/uploads/…` URLs and their display (including a
  second user's listing) are covered only by unit tests and code review. Likewise the
  welcome/auth backdrop was confirmed present *in the APK*, not *on screen*.
- Location permission flows and the export endpoint are wired but never exercised against a
  live backend.

---

## 4. Documentation & deliverables (`docs/`)

| # | Deliverable | Status |
|---|-------------|--------|
| — | `00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md` | ✅ |
| — | `01-DESIGN-SYSTEM.md` | ✅ |
| — | `02-API-CONTRACT.md` | ✅ |
| — | `03-DATA-RETENTION-AND-DELETION.md` | ✅ |
| — | `04-TRACEABILITY-MATRIX.md` | ✅ |
| 7 | `05-TEST-REPORT.md` | ✅ (added 2026-10-16) |
| — | `06-STORE-READINESS-CHECKLIST.md` | ✅ |
| — | `07-EXTERNAL-CREDENTIALS.md` | ✅ |
| — | `08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md` | ✅ |
| 11 | `09-DEPLOYMENT.md` | ✅ |
| — | `10-CROP-MONITORING-API.md` | ✅ (added 2026-10-18) |
| — | `11-SYSTEM-INVENTORY-AND-STATUS.md` | ✅ (added 2026-10-19, Phase 1) |
| 12 | `privacy-policy.md` | ✅ |
| 13 | `terms-of-service.md` | ✅ |
| 14 | `account-deletion.html` | ✅ |
| 3 | `backend/openapi.yaml` | ✅ |

No dead links remain in the README deliverable index.

---

## 5. Repository & DevOps

| Item | Status | Notes |
|------|--------|-------|
| `README.md`, `LICENSE` (MIT), `CONTRIBUTING.md` | ✅ | Deliverable index fully resolves (no dead links) |
| GitHub Actions CI | ✅ | `.github/workflows/ci.yml` — backend tests + Android build/test |
| Gradle wrapper | ✅ | `gradlew`, `gradlew.bat`, `gradle/wrapper/*` committed |
| Room schema export | ✅ | `app/schemas/bw.agriconnect.data.local.AgriDatabase/{1,2}.json` (v2 adds `farm_plots.image_url`) |
| `proguard-rules.pro`, `package-lock.json`, ESLint config | ✅ | |
| Secrets hygiene | 🟡 | `.gitignore` covers `.env`, keystores, `local.properties` — but see §8 |

---

## 6. Supabase schema update

`backend/supabase-update.sql` is a single, **idempotent** script that brings a Supabase
database to the state described by `migrations/001…006`. Run it in the Supabase SQL
editor; it ends with verification queries. It also records the six migration filenames
in `schema_migrations`, so a later `npm run migrate` skips them rather than failing with
"already exists".

`backend/scripts/apply-role-rename.sql` applies **only** migration 006 idempotently, for a
database that is already up to date but still on the old role labels. Run it in the
Supabase SQL editor and it is safe in either order: run it first and the next deploy skips
006, or deploy first (Render runs `npm run migrate` at start) and the script finds the new
labels and does nothing. That bookkeeping matters because 006 is *not* itself re-runnable —
a second `ALTER TYPE … RENAME VALUE 'FARMER' TO 'FARM_OWNER'` fails once `FARM_OWNER`
exists, and that failure would stop the API from starting.

**Verified** on a throwaway PostgreSQL 17 cluster in this environment:

| Check | Result |
|-------|--------|
| Runs cleanly on an empty database | ✅ |
| Re-runnable (3 consecutive runs, no error) | ✅ |
| Schema identical to the official migrations (columns, enums, indexes, constraints, triggers, functions) | ✅ (only expected extra: `schema_migrations`) |
| Bookkeeping records all 6 migrations | ✅ |
| RLS enabled on all 20 tables; `FORCE` never used | ✅ |
| Functional: `set_updated_at` fires; self-employment is rejected | ✅ |

It ends by enabling **Row Level Security** on every table with no policies. The Express
API connects as the table owner (`postgres`), which is exempt from RLS, so the API is
unaffected while Supabase's PostgREST/anon key is locked out. Do **not** add `FORCE ROW
LEVEL SECURITY`, or the API itself will be denied.

---

## 7. How to verify what exists

### Backend
```bash
cd backend
cp .env.example .env          # fill in DB + secrets
npm install
npm run migrate               # or paste backend/supabase-update.sql into Supabase
npm test                      # 152 tests — unit + HTTP smoke (no database needed)
npm start                     # http://localhost:4000  ·  docs at /api-docs
```

### Crop-monitoring API (Python 3.12)
```bash
cd crop-api
python -m venv .venv && ./.venv/Scripts/activate     # Windows
pip install -r requirements-dev.txt
pytest                                               # 34 tests
uvicorn app.main:app --reload                        # http://127.0.0.1:8000/docs
```

### Android (PowerShell, JDK 17)
```powershell
cd android
$env:JAVA_HOME="C:\Users\src.president\agri-tooling\jdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```
Verified in this environment (2026-10-18): **BUILD SUCCESSFUL**, 28/28 unit tests,
`app/build/outputs/apk/debug/app-debug.apk` (`1.1.0`/build 2, 22.5 MB, with
`auth_background.jpg` inside).
The API base URL defaults to the deployed service
(`https://agriconnect-api-f55x.onrender.com/api/v1/`) for both build types, and can be
overridden with `-PapiBaseUrl=…` or `local.properties`, or at runtime in a debug build
(**Server** settings, reachable from Welcome/Sign-in as well as Settings) — see the
README. The former auto-detected Codespaces forwarded port was removed: it could bake a
dead address that surfaced to users as an unexplained "session expired" 401.

---

## 8. Security findings to action

1. **A real database password was committed** to `backend/.env.example` and is present
   in git history (`c6763e5`, `c3f6878`). The working tree now carries a placeholder and
   a warning, but **the credential must be rotated in Supabase** — removing it from the
   file does not remove it from history. Rotate the database password (and any token
   derived from it) before treating this repository as trustworthy.
2. The `.env.example` still names a real Supabase **host**. That is a resource
   identifier, not a credential, but consider replacing it with a placeholder if the
   repo is ever made public.
3. **Sign out now always clears the local session.** Residual risk: if the server-side
   revocation call fails (e.g. a 5xx), the refresh token remains valid server-side until
   it expires; only the device's copy is discarded. Documented rather than hidden.

---

## 9. Suggested next steps (priority order)

1. **Rotate the leaked Supabase database password** (§8) and scrub
   `backend/.env.example` of real infrastructure identifiers.
2. **Run the app once on an emulator** with the backend locally (`10.0.2.2:4000`) and
   walk every role — this is the largest remaining unknown (§3.5).
3. **Add database-backed API tests** (`tests/api/`) for auth lifecycle, sync push/pull
   and order transitions — the highest-risk untested server logic.
4. ~~Write `docs/05-TEST-REPORT.md`~~ — **done 2026-10-16**; the dead README link is fixed.
5. **Wire real providers** (SMS/email, Earth Engine, push) per
   [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md). For crop monitoring,
   enable the `crop-api` service's real GEE path and point the app at it (§2·b).
6. **Add instrumented UI tests** (`androidTest/`) for at least login and the offline
   sync queue; the Hilt runner is already in place. This pass added **no** device run, so the
   image upload/display flow (§3.4) still needs an on-device smoke test.
7. Keep `openapi.yaml`, [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) and
   `ApiService.kt` in sync as endpoints evolve.

---

## 10. Contract of honesty

This document is deliberately pessimistic: unexecuted work is marked 🟡 even when code
exists, and missing files are called out even where the README implies they are present.
If you implement or verify something, **update this file** (and, where relevant,
[`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md)).
Do not mark anything ✅ without running it.

---

## 11. Phase C progress - automatic APK version check and update (client) - 2026-10-20

Server contract (`GET /api/v1/app/version`) was already shipped and committed earlier;
this pass implements the Android client half (Phase C).

**What was added (production code, `android/app/src/main/java/bw/agriconnect/`):**

- `data/remote/dto/UpdateDtos.kt` - `VersionManifestDto` for the public version manifest.
- `data/remote/ApiService.kt` - `@GET("app/version") suspend fun getAppVersion()`.
- `data/update/UpdateDecision.kt` - pure `decide()`: Required when
  `installed < minSupportedVersionCode`, Optional when `installed < latestVersionCode`,
  else None. Defensive: `available=false`, non-positive `latestVersionCode`, or a
  missing/non-HTTPS `downloadUrl` all yield None; `minSupported` is clamped to `1..latest`.
- `data/update/UpdateStateStore.kt` - interface + `SharedPreferencesUpdateStateStore`
  (last successful check timestamp + dismissed version code); `isCheckDue()` /
  `shouldPromptFor()` rules.
- `data/update/UpdateRepository.kt`, `data/update/UpdateChecker.kt` - throttled (6h)
  check orchestration; failures are silent and never prompt or lock out; offline checks
  do not consume the throttle window.
- `data/update/UpdateScheduler.kt` + `data/update/UpdateCheckWorker.kt` - WorkManager
  periodic check (6h, CONNECTED), same pattern as `SyncScheduler`/`SyncWorker`.
- `data/update/Checksums.kt`, `ApkDownloader.kt`, `ApkVerifier.kt`, `ApkInstaller.kt`,
  `UpdateInstallReceiver.kt`, `UpdateException.kt` - HTTPS-only streaming download
  (bare `@ApkDownloadClient`, no bearer token), SHA-256 + package + version + signature
  verification, then `PackageInstaller` session install with a dedicated FileProvider
  fallback.
- `ui/update/UpdateViewModel.kt`, `ui/update/UpdateDialog.kt` - optional vs required
  dialogs, unknown-sources explanation, progress and failure dialogs; hosted once at the
  navigation root (`UpdateDialogHost()` in `ui/AgriConnectRoot.kt`).
- `di/ApkDownloadClient.kt`, `di/NetworkModule.kt` (APK client), `di/AppModule.kt`
  (binds `UpdateStateStore`), `AgriConnectApp.kt` (startup wiring), `AndroidManifest.xml`
  (`REQUEST_INSTALL_PACKAGES`, update FileProvider, `exported="false"` receiver),
  `res/xml/file_paths_updates.xml`.

**Tests added (`android/app/src/test/java/bw/agriconnect/data/update/`):**

- `UpdateDecisionTest` - every `decide()` branch (Required/Optional/None, clamping,
  missing fields, non-HTTPS URL, `available=false`).
- `UpdateVersionApiTest` - MockWebServer contract tests for the manifest wire shape.
- `UpdateStateRulesTest` - throttle + dismissal rules, `sha256Hex`/`toHexLower`/
  `checksumMatches`.

**Verification status:**

- `:app:compileDebugKotlin` - PASSED (BUILD SUCCESSFUL, 0 errors).
- `ui/satellite/SatelliteScreen.kt` was regenerated (ASCII-only, LF, braces balanced);
  the main source set compiles clean, so `:app:compileDebugUnitTestKotlin` and the unit
  suite are no longer blocked.
- `:app:assembleDebug :app:testDebugUnitTest` - PASSED. **94 Android unit tests, 0 failures**;
  APK at `android/app/build/outputs/apk/debug/app-debug.apk` (24.2 MB, `1.1.1`/build 3).
- No on-device run: the actual download/verify/install path (unknown-sources grant,
  PackageInstaller session, FileProvider fallback) has NOT been exercised on a device
  or emulator and remains unverified.

**Docs updated:** `docs/13-ANDROID-RELEASES.md` section 4 rewritten to match the shipped
client behaviour (6h cadence, pure `decide()`, verification steps, install paths,
failure handling). Nothing committed; the coordinator commits.

---

## 12. Phase B/D - unified Satellite screen wired to the monitoring API - 2026-10-20

The Satellite tab now renders the real monitoring contract instead of the earlier
aspirational shapes. The screen is one unified destination with six tabs
(Overview, Chart, Weather, Growth, Events, Alerts); no separate app or dashboard.

**Contract alignment (Android side, `android/app/src/main/java/bw/agriconnect/`):**

- `domain/model/satellite/MonitoringModels.kt` - rewritten to the server shapes:
  `SatelliteOverview` (with `disclaimer`/`generatedAt`), `PlotGeometry.reason`,
  `ObservationStatus`, `IndexSummary` (available/count/trend/dataAgeDays),
  `GrowthCycle` (single nullable confidence), `VegetationDrop` (nullable
  classification/label/explanation/nextStep, `List<String>` evidence),
  `PlotAlert` (nullable plotId/farmerId), `WeatherData`/`WeatherDaily.soilMoisturePct`
  (already a percentage), `WeatherSummary`, `MonitoringQuality`, `ModelStatus`,
  `BoundaryPreview`, `SetBoundaryResult`, `PlotSummary`; enums use the real
  `TRANSIENT_DIP` / `POSSIBLE_HEAT_OR_DRY_STRESS` names.
- `data/remote/dto/SatelliteDtos.kt` - authoritative wire types (`PlotRefDto`,
  `PlotGeometryDto`, `PlotObservationDto`, `GrowthCycleDto`, `VegetationDropDto`,
  `PlotEventDto`, `PlotAlertDto`, `WeatherResponseDto`, `QualityDto`, `ModelStatusDto`,
  `BoundaryDto` + wrapped request bodies, `PagedEventsDto`, `AlertListDto`,
  `LatestPlotsDto`, `AlertStatusUpdateRequest(action)`).
- `data/remote/dto/Mappers.kt` - mapping for every DTO; `parseClassification` accepts
  the legacy `NOISE`/`POSSIBLE_HEAT_STRESS` strings as aliases.
- `data/remote/ApiService.kt` - 9 monitoring endpoints aligned to the new DTOs.
- `data/repository/SatelliteMonitoringRepository.kt` - unpacks paged envelopes,
  maps `AlertStatus` to `ACKNOWLEDGE|DISMISS|REOPEN`, and reports weather
  unavailability honestly (no invented numbers).
- `ui/satellite/SatelliteViewModel.kt` - overview derives the legacy `Ndvi` from the
  newest observation (series arrives oldest-first, picked by date), events refresh
  after create/update/delete, and alert status refresh falls back to the on-screen
  plot when the response omits `plotId`.
- `ui/satellite/SatelliteScreen.kt` - regenerated cleanly (ASCII-only) and includes:
  - a freshness banner ("Newest clear satellite image: <date> (N days old)"),
    the observation-status note and the disclaimer banner;
  - `EventsTab` + `EventDialog` + `EventRow`: farmer event log with confirm/correct for
    SYSTEM rows, edit/delete for FARMER rows, a training-pending footnote, YYYY-MM-DD
    validation and a 2000-character notes cap;
  - `BoundaryEditorSheet`: Method A rotated rectangle (centre, width, length, bearing)
    and Method B custom polygon (add/remove corners, range checks), both measured by
    the server via the preview endpoint before saving, plus a save that warns old
    observations are invalidated.
- `data/update/**` + `ui/update/**` - Phase C APK update client (section 11).

**Backend additive changes:**

- `services/satelliteAnalysis.js` - `summarise(series, {now, index})` parameterised by
  index; `analyseSeries` now also returns `eviSummary` (so the app can chart and
  summarise both indices from one request). `summary` remains NDVI-only.
- `modules/satellite/satellite.monitoring.service.js` - returns `eviSummary`.
- `modules/satellite/satellite.schemas.js` - `daysBack` accepted as an alias of `days`
  on the overview and weather queries; the route resolves `days ?? daysBack`.

**Docs corrected to the real payloads:** `docs/02-API-CONTRACT.md` §2.6.2 (endpoint
table + response excerpts + key guarantees), `backend/openapi.yaml` (satellite paths and
all satellite component schemas), `docs/12-SATELLITE-MONITORING.md` (classification
names, alert keys, growth-cycle and model-status fields, geometry request/response
shapes).

**Tests:** backend suite **281 passed / 281, 17 suites** (added `eviSummary` unit tests
and `daysBack` alias schema tests). Android `:app:assembleDebug :app:testDebugUnitTest`
passes: **94 unit tests, 0 failures**, APK at
`android/app/build/outputs/apk/debug/app-debug.apk` (24.2 MB, `1.1.1`/build 3).

---

## 13. Shared farm & plot source of truth — 2026-10-20

**Finding (verified in code, not assumed):** Farm Management and Satellite Monitoring
already share one canonical record — a single `farm_plots` table, one server uuid
`plotId`, and the same Android `FarmPlotRepository` / `farm_plots` Room table. Satellite
child rows (`plot_observations`, `plot_events`, `plot_alerts`) all reference
`farm_plots(id)`. There is **no** `satellite_plots` table, no geojson-only registry and no
duplicate farm record. Navigation already passes the same `plotId` both ways
(`farmDetail(plotId)` ↔ `satellite(plotId)`). So the correct action was to close two real
defects rather than build a second system.

**Defect 1 — the same boundary edit behaved differently on each screen.** The satellite
boundary save (`PUT /satellite/plots/:id/boundary`) cleared the stale `plot_observations`,
but `PATCH /farms/plots/:id` did not — so editing a boundary in Farm Management left NDVI
readings computed for the **old** shape driving the satellite charts and alerts. Fixed by
extracting one shared rule and applying it on **all three** boundary-write paths:

- `boundaryChanged(before, after)` + `invalidateObservationsForPlot(plotId)` in
  `satellite.service.js` (single implementation, not two copies).
- Online Farm Management — `PATCH /farms/plots/:id` now reports `observationsInvalidated`.
- Online Satellite — the boundary route uses the shared helper.
- **Offline replay** — a new `afterUpdate` hook on the `FarmPlot` sync descriptor runs the
  same rule *inside the sync transaction* (`sync.entities.js` / `sync.service.js`), so an
  offline boundary edit reconciles to the same server state as an online one.
- A re-save that resends the identical ring is **not** destructive: `boundaryChanged`
  normalises the closing vertex and 6-dp precision, so cosmetic differences do not wipe
  history. Events and alerts are deliberately preserved (they carry their own dates).

**Defect 2 — a role could see a plot but not monitor it.** Farm plot reads are
`farmOwnerOrFarmer`; every satellite endpoint was `farmOwnerOnly`, so a hired `FARMER`
could see the plot in Farm Management and get a 403 one tap later on Satellite. Fixed by
aligning the guards: satellite **reads** (`overview`, `weather`, `events` GET, `alerts`
GET, `plots/latest`, NDVI history/latest/one) now use `farmOwnerOrFarmer` and resolve the
**owner's** farmer id via `resolveOwnerFarmerId`; **writes** (boundary preview/save, NDVI
compute, event create/edit/delete, alert status) stay `farmOwnerOnly`. The outer
`router.use` blanket guard in `satellite.routes.js` was the actual gate and was corrected
too.

**Hardening while there:** `resolveOwnerFarmerId` now refuses a role that is neither
`FARM_OWNER` nor `FARMER` explicitly. A `BUYER` previously fell through to the employer
lookup, so a stray `employer_farmer_id` on their row would have handed them someone else's
farm. Not reachable through the route guards, but this is the ownership primitive, so it
now enforces the rule itself. A test caught it.

**Tests added (0 DB required):**
- `tests/unit/sharedPlots.test.js` (10) — `boundaryChanged`: identical ring, missing
  closing vertex, float noise, moved vertex, redrawn shape, null↔shape, `Feature`-wrapped
  and bare-ring shapes, winding order, and agreement with `ringFromGeoJson`.
- `tests/unit/plotOwnership.test.js` (6) — `resolveOwnerFarmerId`: owner gets own id with no
  query, employed farmer gets the employer's id, unlinked/missing buyer/unauthenticated all
  refused. Uses `jest.unstable_mockModule` to mock the db (new, documented pattern).
- `tests/unit/sync.entities.test.js` (+5) — the FarmPlot `afterUpdate` hook clears the
  series on a real change, leaves it on a no-op, handles add/remove of a boundary, and runs
  on the caller's transaction client (never its own connection).

**Docs:** `docs/12-SATELLITE-MONITORING.md` §8a (new) documents the one-table/one-id
contract, the child-row keys, and the three boundary-write paths that share the
invalidation rule; `docs/02-API-CONTRACT.md` §2.4/§2.6.1 updated for `observationsInvalidated`
and the aligned satellite read roles; `backend/openapi.yaml` `PATCH /farms/plots/{id}`
documents the field (62 paths / 67 schemas, parses).

**Verification:** backend **302 passed / 302, 19 suites** (was 281/17). Android
`:app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL** — 94 tests / 12 suites / 0
failures; APK at `android/app/build/outputs/apk/debug/app-debug.apk` (24,218,665 bytes).

**Blockers (unchanged):** no device/emulator, so acceptance scenarios 1–8 are proven at the
unit/route level but not yet on a running app; no DB in CI, so `invalidateObservationsForPlot`
is tested via the pure `boundaryChanged` predicate and the sync hook, not a live DELETE.

## 14. Google Maps plotting + livestock listing units — 2026-10-20

**Google Maps (Android).** The app previously had **no map at all**: `osmdroid` was declared
as a dependency but never used, and a plot boundary was typed as lat/lng text. Added the
Maps SDK for Android (`play-services-maps` 19.0.0 + `maps-compose` 6.4.1) and removed the
unused `osmdroid` and its `WRITE_EXTERNAL_STORAGE` permission.

- `ui/components/PlotBoundaryMap.kt` (new) is the one map component — tap to add a corner,
  drag a pin to move one. It edits the same `GeoVertex` list as the coordinate fields, so the
  map and the manual editor are two views of **one** boundary.
- It is embedded in the shared `BoundaryEditor`, so Farm Management **and** Satellite
  Monitoring both get it from a single implementation; `addVertex`/`moveVertex` were added to
  `FarmViewModel`/`FarmDetailViewModel` and the polygon branch of the Satellite
  `BoundaryEditorSheet`.
- The map never computes the stored area — the server measures real geometry
  (`services/geometry.js`). The on-screen figure is labelled approximate.
- **Credential handling:** the key is read from the git-ignored `android/local.properties`
  (`mapsApiKey=...`) and injected as `BuildConfig.MAPS_API_KEY` + the
  `com.google.android.geo.API_KEY` manifest placeholder. There is **no default**; when absent
  the build still succeeds and the map draws an explained "Map unavailable" panel rather than
  a blank grey rectangle. `git grep "AIzaSy"` returns nothing — no key is tracked.
- maps-compose 6.x has no `Marker.onDragEnd` and keeps `MarkerState.dragState` internal, so a
  finished drag is read from `MarkerState.position` via `snapshotFlow`
  (`.distinctUntilChanged()`), which commits once per completed drag instead of once per pixel.

**Google Weather (backend, optional).** `src/services/googleWeather.js` (new) adds Google
Weather as an opt-in second source (`WEATHER_PROVIDER=GOOGLE` + server-side
`GOOGLE_WEATHER_API_KEY`). Open-Meteo stays the default and is unchanged. Google supplies a
**forecast** and only 24 h of history — not the multi-week archive the drought/heat rules
need — so the fields it cannot supply (14-day rainfall, soil moisture) come back **null, never
zero**, and daily mean temperature is stated as the midpoint of max/min. The key is never
echoed into a response, and an HTTP failure is reported by status code only because the request
URL carries the key.

**Livestock units (marketplace).** Farmers selling animals could not list them: the UI was a
free-text unit box ("Unit (kg, bag, crate…)") while the server validates a fixed enum, so
typing "herd"/"cattle" produced a 400 at publish. Fixed end to end:

- Added `herd` and `flock` alongside the existing `head` (one animal): `herd`/`flock` sell a
  whole group as one lot (quantity 1, one price). `LISTING_UNITS` in
  `marketplace.schemas.js`, the `produce_listings.unit` CHECK constraint
  (`migrations/008_livestock_units.sql`, additive + idempotent), the OpenAPI `ListingUnit`
  enum and the Android `Unit` enum now all agree.
- The sync entity registry kept its **own private copy** of the unit list; it now imports
  `LISTING_UNITS`, so a listing valid on the REST API can no longer be silently rejected on
  the offline path.
- Android: the create-listing screen uses a grouped Produce/Livestock chip picker instead of
  free text, so an invalid unit cannot be entered. The ViewModel holds the enum (not a
  `String`), sends `unit.wire`, and rejects a fractional livestock quantity locally before a
  round-trip.

**Docs:** `docs/14-GOOGLE-MAPS.md` (new) — both keys, why they are separate, the Cloud Console
restrictions, the debug/release SHA-1 entries, and key-rotation steps;
`docs/02-API-CONTRACT.md` §2.7 documents the unit enum; `backend/openapi.yaml` gains a
reusable `ListingUnit` schema (62 paths / 68 schemas, parses); README feature row 26.

**Verification:** backend **332 passed / 332, 21 suites** (was 302/19; +16 Google-weather,
+14 livestock-unit). Android `:app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**
— **100 tests / 13 suites / 0 failures** (was 94/12); APK at
`android/app/build/outputs/apk/debug/app-debug.apk` (23,727,981 bytes).

**Blockers / not verified:** no device or emulator, so the map, the unit picker and the update
download path are verified by compilation and unit tests only — **none has been seen rendering
on a device**. Google Weather and the Maps SDK have not been exercised against the live
services (no billed calls were made); the tests use injected fakes. Migration 008 has not been
run against a database (no DB in CI); it is applied by `npm run migrate` on deploy or pasted
into the Supabase SQL editor.

---

## 15. Satellite "sight" (NDVI colour map) + farm-wide alerts — 2026-10-21

**The problem.** The Satellite screen had **no image at all** — only numbers. A farmer could
see that NDVI was 0.42 without seeing *where* the crop was strong or thin. Separately, alerts
were scoped to one plot, so a farmer with several plots got no answer to "which of my farms
need me?". Both gaps are now closed.

### 15.1 The honesty problem, and how it is solved

The pipeline measures **one number per date per plot** — the mean of the pixels inside the
boundary. It returns no per-pixel raster. There are therefore only two truthful things a
colour map can do: plot a real array, or build a texture that preserves the measured mean and
label it as texture. It does the second.

- `services/ndviGrid.js` (new) builds the grid so the **average of the in-boundary cells
  equals the measured plot mean** — that average is real. The **pattern** across the cells is
  generated from `sha256(plotId + date)` and is **not** an observation.
- Every response carries `variationSimulated: true` plus a `note` saying so, and the client is
  required to display: *"Read the average, not the pattern."* Presenting the texture as a real
  image would be a fabricated reading.
- The texture is deterministic, so the same plot and date always draw the same picture.
- An amplitude clamp keeps the grid's mean exact: if the requested spread would push cells past
  the scale limits, the amplitude is shrunk rather than letting clamping flatten cells and
  shift the average. A test asserts the mean holds across the full index range.
- Cells outside the boundary are **`null`, not `0`**, so the client leaves them blank and the
  field's shape is visible. Mapping `null` to `0` would invent bare soil along the plot edge.
- The grid is built from the **stored** series, never a fresh provider call, so the picture
  always agrees with the numbers beside it.

### 15.2 What was added

**Backend**
- `GET /satellite/colour-map` — the per-pixel grid, legend, coverage, staleness, and a
  server-computed plain-language reading (`describeIndex`). Returns `available: false` with a
  `reason` when there is no boundary or no usable observation.
- `GET /satellite/alerts/by-farm` — **every** live plot the caller owns, grouped by farm, with
  `totals`. Three attention states are distinct: open alerts, `isStale`, and `noData`. A plot
  with no reading is returned as `needsAttention: true` with a plain reason — **silence is not
  health**, so a forgotten field cannot look fine by being absent.
- `describeIndex` guards `null`/`''` **before** `Number()`, because `Number(null)` is `0` and
  would have rendered "NDVI is 0.00" for a missing reading — a real bug caught by its test.

**Android**
- `ui/components/ColourMapView.kt` (new) renders the grid on a `Canvas` (a 24×24 grid is 576
  cells — far too many layout nodes), maps values through the server's own legend stops, and
  draws the legend as a proportional strip plus expandable plain-language rows.
- `SatelliteSightCard` in the Overview tab: index switch (NDVI/EVI), honesty chips
  (Demo data / Not current / date) **above** the image, the measured field average, the
  plain-language interpretation, and an always-visible "read the average, not the pattern"
  banner. When the data is MOCK an additional banner says so outright.
- `FarmAttentionSummary` in the Alerts tab: headline counts, then farms and plots needing
  attention, each tappable to switch the screen to that plot, with the reason shown.

**Docs / contract:** `docs/12-SATELLITE-MONITORING.md` §9–§10 document the colour-map honesty
rule and the farm-wide attention states; `backend/openapi.yaml` gains
`/satellite/colour-map`, `/satellite/alerts/by-farm` and the `ColourMap`, `AlertsByFarm`,
`FarmAlertGroup`, `FarmAlertPlot` schemas (64 paths / 72 schemas, parses).

**Verification:** backend **372 passed / 372, 23 suites** (was 332/21; +22 `ndviGrid`,
+11 satellite-monitoring schema/`describeIndex`, +7 `alertsByFarmSchema`). Android
`:app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL** — **114 tests / 15 suites /
0 failures** (was 100/13; +6 colour-map rendering, +7 wire-mapping); APK at
`android/app/build/outputs/apk/debug/app-debug.apk` (24,137,587 bytes).

**Fixed after release (§15.1):** `/satellite/alerts/by-farm` returned **HTTP 500** in
production. The query joined a `farms` table and a `farm_plots.farm_id` column that this
schema never creates, and selected `plot_alerts.kind` where the column is `alert_type`. The
endpoint now groups the owner's plots under the owner's account (the farm label is the
owner's name) and the query reads only real columns;
`backend/tests/unit/alertsByFarmSchema.test.js` pins every `alertsByFarm` query against the
migration schema so the drift cannot come back.

**Blockers / not verified:** same as §14 — no device or emulator, so the colour map and the
farm-wide alert list are verified by compilation and unit tests only and **have not been seen
rendering on a device**. The `alertsByFarm` SQL has not been run against a database (no DB in
CI); the fix is verified by the schema-contract test and by hitting the live endpoint. With
no Earth Engine credentials the mean behind the picture is labelled MOCK, and the UI says so.
