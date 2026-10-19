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
| **Android build** | 🟢 `:app:assembleDebug` produces a 24.0 MB APK — `1.1.1` (build 3), re-run 2026-10-19 |
| **Android unit tests** | 🟢 50/50 pass, 9 suites, 0 failures (`:app:testDebugUnitTest`, re-run 2026-10-19) |
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
