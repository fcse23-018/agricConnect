# AgriConnect — Implementation Status & Handover

> **Purpose.** A single, honest map of what exists in this repository, what is
> stubbed or mocked, and what is still missing — so a developer joining the project
> can orient in minutes and know exactly where to start.
>
> **Last updated:** 2026-10-15 · **Baseline commit:** `c3f6878` (+ uncommitted work
> described below) · **Platform:** Windows, JDK 17, Node 24, PostgreSQL 17
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
| **Backend tests** | 🟢 73/73 pass (`npm test`, exit 0) — unit + HTTP smoke |
| **Backend ↔ Supabase schema** | 🟢 `backend/supabase-update.sql` verified against PostgreSQL 17 (§6) |
| **Android data + domain layer** | 🟢 Room, Retrofit, sync engine, session, 11 repositories |
| **Android app shell** | 🟢 `AgriConnectApp`, `MainActivity`, Hilt modules, nav host all present |
| **Android UI** | 🟢 26 Compose screens, role-based navigation, offline/sync affordances |
| **Android build** | 🟢 `:app:assembleDebug` produces a 22.3 MB APK (re-run 2026-10-16) |
| **Android unit tests** | 🟢 12/12 pass (`:app:testDebugUnitTest`, re-run 2026-10-16) |
| **Android runtime** | 🟡 Never launched on a device/emulator — no instrumented test run |
| **External providers** | 🔴 SMS/email, Earth Engine and push are not configured |
| **Docs / deliverables** | 🟢 All referenced docs present — `docs/05-TEST-REPORT.md` added 2026-10-16 |

---

## 1. Repository layout

```
agriConnect/
├─ android/    Native Android app (Kotlin · Jetpack Compose · Room · Hilt · WorkManager)
├─ backend/    Node.js/Express REST API + PostgreSQL migrations + Supabase update script
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
| Migrations (5, ordered) + runner + seed | `backend/migrations/`, `src/db/migrate.js`, `src/db/seed.js` | `001_init` … `005_employee_link` |
| Auth middleware (JWT bearer) + role guard (RBAC, 3 roles) | `src/middleware/auth.js`, `roles.js` | UI is never the enforcement point |
| Validation + error envelope + rate limiting + idempotency | `src/middleware/` | Never leaks stack traces |
| Auth module (register→login→refresh→reset→verify) | `src/modules/auth/` | Full token lifecycle |
| Profile, avatar, export, employee-link, deletion lifecycle | `src/modules/users/`, `account/`, `public/` | Re-auth, grace period, retention ledger |
| Farms/plots, harvests, livestock | `src/modules/farms/`, `farm/` | GeoJSON boundaries |
| Sales, expenses, financial summary | `src/modules/finance/` | Farmer-only |
| Satellite NDVI (mock + real GEE path) | `src/modules/satellite/`, `services/gee.js`, `services/ndvi.js` | NDVI arithmetic unit-tested |
| Marketplace listings + orders workflow | `src/modules/marketplace/`, `orders/` | Transactional stock, no payments |
| Notifications feed + sync push/pull + audit log | `src/modules/notifications/`, `sync/`, `services/audit.js` | In-app only |
| Tests | `tests/unit/*` (5 files), `tests/api/smoke.test.js` | **73 tests, all passing** |
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
| Repositories | `data/repository/` (11) | Auth, FarmPlot, Harvest, Livestock, Finance, Marketplace, Order, Notification, Satellite, Account |
| Result type | `core/result/AppResult.kt` | `AppResult.Success/Failure`, `AppError.message/isNetwork` |
| Design system | `ui/theme/` | Tokens match [`docs/01-DESIGN-SYSTEM.md`](docs/01-DESIGN-SYSTEM.md) |
| Reusable components | `ui/components/` (14 files) | Scaffold, surfaces, buttons, dialogs, state views, chips, segmented control, chart, badges, settings rows, logo |
| Navigation | `ui/AgriConnectRoot.kt`, `ui/navigation/` | Root `NavHost`, `MainScaffold` chrome, `AgriNavItems.forRole`, `homeRouteFor` |
| Screens | `ui/**` (26 `*Screen.kt`) | See §3.2 |
| Unit tests | `app/src/test/...` (4 files) | **12/12 passing** |
| Instrumented test scaffold | `app/src/androidTest/.../HiltTestRunner.kt` | Runner exists; no test classes yet |

### 3.2 Implemented screens ✅

Splash · Welcome · Onboarding · Login · Register · Forgot password · Dashboard ·
Farm (plots) · Farm detail · Harvests · Livestock · Satellite insights · Finance ·
Marketplace · Listing detail · Create listing · Orders · Order detail ·
Notifications · Profile · Settings · Notification preferences · Privacy & consent ·
Account deletion · Offline & sync · Help.

Role-based bottom navigation: **FARMER** home/farms/finance/market/profile ·
**FARM_EMPLOYEE** home/farms/alerts/profile · **BUYER** market/orders/alerts/profile.

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
- **Sign out** always clears the local session, even when the server call fails (§8).
- Notification taps route only on a **known** `relatedEntityType`; anything unknown is
  ignored rather than guessed.

### 3.4 Fixed in the latest pass ✅

| Fix | File |
|-----|------|
| Legal consents (`acceptTerms`, `acceptPrivacy`) were dropped by kotlinx.serialization defaults → `@EncodeDefault(ALWAYS)` | `data/remote/dto/AuthDtos.kt` |
| Profile / Settings "Sign out" navigated but **never ended the session** → VMs now call `AuthRepository.logout()` | `ui/profile/ProfileViewModel.kt`, `ui/settings/SettingsViewModel.kt`, matching screens |
| `AuthRepository.logout/logoutAll` only cleared the session on some error codes → local session is always cleared | `data/repository/AuthRepository.kt` |

### 3.5 Not verified ❌

- The app has **not been launched**. No emulator/device smoke test, no screenshot, no
  instrumented test. Compose layout/visual fidelity to the design reference is
  therefore **unverified** — only the code paths were reviewed and compiled.
- Location permission flows, avatar upload and the export endpoint are wired but
  never exercised against a live backend.

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
| Room schema export | ✅ | `app/schemas/bw.agriconnect.data.local.AgriDatabase/1.json` |
| `proguard-rules.pro`, `package-lock.json`, ESLint config | ✅ | |
| Secrets hygiene | 🟡 | `.gitignore` covers `.env`, keystores, `local.properties` — but see §8 |

---

## 6. Supabase schema update

`backend/supabase-update.sql` is a single, **idempotent** script that brings a Supabase
database to the state described by `migrations/001…005`. Run it in the Supabase SQL
editor; it ends with verification queries. It also records the five migration filenames
in `schema_migrations`, so a later `npm run migrate` skips them rather than failing with
"already exists".

**Verified** on a throwaway PostgreSQL 17 cluster in this environment:

| Check | Result |
|-------|--------|
| Runs cleanly on an empty database | ✅ |
| Re-runnable (3 consecutive runs, no error) | ✅ |
| Schema identical to the official migrations (columns, enums, indexes, constraints, triggers, functions) | ✅ (only expected extra: `schema_migrations`) |
| Bookkeeping records all 5 migrations | ✅ |
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
npm test                      # 73 tests — unit + HTTP smoke (no database needed)
npm start                     # http://localhost:4000  ·  docs at /api-docs
```

### Android (PowerShell, JDK 17)
```powershell
cd android
$env:JAVA_HOME="C:\Users\src.president\agri-tooling\jdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```
Verified in this environment: **BUILD SUCCESSFUL**, 12/12 unit tests,
`app/build/outputs/apk/debug/app-debug.apk`. The API base URL defaults to
`http://10.0.2.2:4000/api/v1/` (debug) and `https://api.agriconnect.bw/api/v1/` (release),
and can be overridden with `-PapiBaseUrl=…`, `local.properties`, or the auto-detected
Codespaces forwarded port — see the README.

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
   [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md).
6. **Add instrumented UI tests** (`androidTest/`) for at least login and the offline
   sync queue; the Hilt runner is already in place.
7. Keep `openapi.yaml`, [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) and
   `ApiService.kt` in sync as endpoints evolve.

---

## 10. Contract of honesty

This document is deliberately pessimistic: unexecuted work is marked 🟡 even when code
exists, and missing files are called out even where the README implies they are present.
If you implement or verify something, **update this file** (and, where relevant,
[`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md)).
Do not mark anything ✅ without running it.
