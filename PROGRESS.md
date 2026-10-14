# AgriConnect — Implementation Status & Handover

> **Purpose.** A single, honest map of what exists in this repository, what is
> stubbed or mocked, and what is still missing — so a developer joining the project
> can orient in minutes and know exactly where to start.
>
> **Last updated:** 2026-10-14 · **Baseline commit:** `d637a0d`
>
> **How to read the status markers**
> ✅ **Done** — implemented and, where noted, verified · 🟡 **Partial / unverified** —
> code exists but is incomplete, mocked, or not executed · ❌ **Missing** — not present.
>
> Nothing here should be read as a claim that something *works*. Where a result was
> not actually executed it is marked 🟡. See also
> [`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md).

---

## 0. TL;DR for a new contributor

| Area | State |
|------|-------|
| **Backend REST API** | 🟢 Largely complete — every endpoint in the contract is implemented |
| **Backend tests** | 🟡 5 unit tests only; no API/integration tests |
| **Android data + domain layer** | 🟢 Substantially complete (Room, Retrofit, sync engine, session) |
| **Android UI (app shell)** | 🔴 **Not buildable** — no `MainActivity`, no `Application` class, no DI, no screens/navigation |
| **Android repository layer** | 🔴 Only a helper exists; no concrete repositories |
| **Docs / deliverables** | 🟡 9 of 14+ written deliverables present; 4 referenced files missing |
| **CI / DevOps** | 🟢 GitHub Actions added; no Gradle wrapper, no lockfile |

**The single most important fact:** the Android app **does not currently compile into a
runnable app**. `AndroidManifest.xml` declares `android:name=".AgriConnectApp"` and an
activity `.MainActivity`, but **neither class exists**. Closing that gap (plus Hilt
modules) is the critical path for the mobile side. The backend is much further along.

---

## 1. Repository layout

```
agriConnect/
├─ android/    Native Android app (Kotlin · Jetpack Compose · Room · Hilt · WorkManager)
├─ backend/    Node.js/Express REST API + PostgreSQL migrations
├─ docs/       Written deliverables (requirements, design, API contract, …)
├─ .github/    CI workflow (added)
├─ README.md   Project overview + deliverable index
├─ CONTRIBUTING.md
├─ LICENSE     MIT
└─ PROGRESS.md ← this file
```

---

## 2. Backend (`backend/`) — Node.js / Express / PostgreSQL

**Stack:** native ESM (`"type": "module"`), Express 4, PostgreSQL (`pg`), Zod validation,
JWT auth, Helmet/CORS/compression, Swagger UI.

### 2.1 What is done ✅

| Component | Location | Notes |
|-----------|----------|-------|
| App bootstrap, security headers, CORS, request IDs | `src/app.js` | Versioned at `/api/v1`; health at `/health` |
| Config (env-driven) + production secret assertions | `src/config/index.js` | Refuses to boot in prod with dev secrets |
| DB pool, transactions, healthcheck | `src/db/index.js` | |
| Migrations (5, ordered) + runner + seed | `backend/migrations/`, `src/db/migrate.js`, `src/db/seed.js` | `001_init`, `002_domain`, `003_marketplace`, `004_sync_notifications_retention`, `005_employee_link` |
| Auth middleware (JWT bearer) | `src/middleware/auth.js` | |
| Role guard (RBAC, 3 roles) | `src/middleware/roles.js` | UI is never the enforcement point |
| Validation + error envelope | `src/middleware/errorHandler.js` | Never leaks stack traces |
| Rate limiting | `src/middleware/rateLimit.js` | auth / sync / public tiers |
| Idempotency | `src/middleware/idempotency.js` | `register`, `orders`, `sync/push` |
| Auth module (register→login→refresh→reset→verify) | `src/modules/auth/` | Full token lifecycle |
| Profile + avatar + export + employee-link | `src/modules/users/` | |
| Account deletion lifecycle | `src/modules/account/`, `src/modules/public/` | Re-auth, grace period, retention ledger |
| Farms & plots (GeoJSON) | `src/modules/farms/` | |
| Harvests & livestock | `src/modules/farm/` | |
| Sales, expenses, financial summary | `src/modules/finance/` | Farmer-only |
| Satellite NDVI (mock + real GEE path) | `src/modules/satellite/`, `src/services/gee.js`, `src/services/ndvi.js` | |
| Marketplace listings | `src/modules/marketplace/` | DRAFT→PUBLISHED lifecycle |
| Orders workflow | `src/modules/orders/` | Transactional stock, no payments |
| Notifications feed + device tokens | `src/modules/notifications/` | In-app only |
| Sync push/pull engine | `src/modules/sync/` | Per-item txn, Last-Write-Wins |
| Audit log | `src/services/audit.js` | |
| OpenAPI 3 spec | `backend/openapi.yaml` | **Added** — 53 paths; drives `/api-docs` |
| Dockerfile + docker-compose | `backend/Dockerfile`, `backend/docker-compose.yml` | Postgres + migrate + api |

**Every endpoint documented in [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) §2.1–§2.10
is implemented.** No documented endpoint is missing.

Undocumented-but-implemented endpoints (should be reconciled into the contract):
`GET /api/v1/version`, `GET /satellite/ndvi/latest`, `POST /users/me/employer-link`.

### 2.2 What is partial / mocked 🟡

| Item | File | Reality |
|------|------|---------|
| Email/SMS delivery | `src/services/delivery.js` | `SMTP` and `Twilio` **throw "not configured"**. Only `NONE`/`OUTBOX` work, writing to `message_outbox`. Codes never actually sent. |
| Earth Engine / Sentinel-2 | `src/services/gee.js` | Real GEE path is written but unverified; default runs a **deterministic mock** (`GEE_ENABLED=false`). Results tagged with `source`. |
| Push notifications | `src/modules/notifications/` | In-app feed works; **no FCM/APNs** delivery. `device_tokens` are stored but unused. |
| Integration tests | `backend/tests/` | Only `tests/unit/*` (5 files). `npm run test:api` points at a non-existent `tests/api/`. |

### 2.3 What is missing ❌

- `package-lock.json` — CI uses `npm install`; a lockfile is recommended.
- ESLint config — `npm run lint` (`eslint src tests`) has **no config file** and would fail.
- `tests/api/` integration suite (referenced by `package.json`).
- Real provider credentials — see [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md).

---

## 3. Android (`android/`) — Kotlin / Compose / Hilt / Room

**Stack:** Kotlin 2.0.21, AGP 8.7.3, Jetpack Compose (Material 3), Room, Hilt, WorkManager,
Retrofit + kotlinx.serialization, Coil, osmdroid, Vico. JDK 17, `compileSdk` 35, `minSdk` 24.

### 3.1 What is done ✅

| Layer | Files | Notes |
|-------|-------|-------|
| Build config | `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/settings.gradle.kts`, `gradle/libs.versions.toml` | Version catalog; per-buildType `API_BASE_URL` |
| Manifest | `android/app/src/main/AndroidManifest.xml` | Permissions, FileProvider, WorkManager-via-Hilt |
| Domain models | `domain/model/{User,Enums,FarmModels}.kt` | Framework-free |
| Local DB (Room) | `data/local/AgriDatabase.kt`, `Converters.kt`, `DatabaseProvider.kt` | |
| Entities + DAOs | `data/local/entity/*`, `data/local/dao/*` | Plots, harvests, livestock, sales, expenses, listings, orders, notifications, NDVI, sync queue |
| Networking | `data/remote/ApiService.kt` | Declares the **entire** REST surface |
| HTTP plumbing | `AuthInterceptor.kt`, `TokenAuthenticator.kt`, `NetworkErrorParser.kt` | Silent refresh; no recursion |
| DTOs + mappers | `data/remote/dto/*` | |
| Session / tokens | `data/session/{TokenStore,SessionManager}.kt` | Encrypted token storage |
| Sync engine | `data/sync/{SyncEngine,SyncQueue,SyncApplier,ConflictResolver,SyncScheduler,SyncWorker}.kt` | WorkManager-based, offline-first |
| Core utilities | `core/{result/AppResult,util/*}` | Result type, connectivity, validators, money/NDVI formatting |
| Design system | `ui/theme/*` (Color, Type, Shape, Theme) | |
| Reusable components | `ui/components/*` (9 files) | Buttons, chips, text fields, dialogs, surfaces, state views, status, scaffold, logo |
| Unit tests | `app/src/test/.../{AgriDatabaseTest,AuthApiTest,SyncApiTest,TokenAuthenticatorTest}.kt` | 4 files |
| Resources | Icons, splash, themes, strings, backup rules | |

### 3.2 What is missing ❌ — **this is the critical path**

These gaps mean the app **cannot launch** and likely cannot be assembled into a
runnable APK:

| Missing piece | Declared / referenced by | Why it matters |
|---------------|--------------------------|----------------|
| **`AgriConnectApp`** (`@HiltAndroidApp` Application) | `AndroidManifest.xml` line 28 | App has no entry point; crashes on start |
| **`MainActivity`** (`@AndroidEntryPoint`, `setContent { … }`) | `AndroidManifest.xml` line 40 | No UI host |
| **Hilt DI modules** (`di/`) | build.gradle applies Hilt/KSP | Nothing provides `AgriDatabase`, `Retrofit`, `ApiService`, `SessionManager`, etc. |
| **Navigation graph** | README claims `ui/navigation` | No route structure |
| **Screens** (`ui/screens/`) | README claims `ui/screens` | No login, dashboard, plots, finance, marketplace, orders, notifications, profile, sync UI |
| **ViewModels** | — | No state holders / business logic binding |
| **Repository layer** | `data/repository/` (only `RepositorySupport.kt`) | `ApiService` + Room are not wired to the UI |
| **`proguard-rules.pro`** | `app/build.gradle.kts` release block | Release build references a file that does not exist |
| **`androidTest/` + `HiltTestRunner`** | `testInstrumentationRunner` in build.gradle | Instrumented tests cannot run |
| **Room schema export dir** (`app/schemas/`) | `ksp { room.schemaLocation }` | Generated at build; should be committed for migration review |
| **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) | README quick-start (`./gradlew …`) | Local builds need a system Gradle |
| **Push wiring** (FCM) | — | Matches docs L6: notifications are in-app only |

> **Bottom line:** the Android **data/domain layers and design system are strong**, but
> the **application shell and UI are absent**. A developer should expect to build
> `AgriConnectApp`, Hilt modules, repositories, ViewModels and the Compose screens.

### 3.3 Client ⇄ server mismatches (latent bugs) 🟡

These are not yet exercised because the repository layer is unimplemented, but they
will bite as soon as it is:

1. **Path mismatch** — `ApiService` calls `POST devices`; the backend serves device
   registration at `POST /notifications/devices`. The call as written returns `404`.
2. **Response-envelope mismatches** — the client expects bare DTOs where the server
   returns a wrapper:

   | Client declaration | Server actually returns |
   |--------------------|-------------------------|
   | `getMe()`, `patchMe()`, `uploadAvatar()`, `employerLink()` → `UserDto` | `{ user: … }` |
   | `putNotificationPreferences()` → prefs | `{ user: … }` |
   | `markRead()` → `NotificationDto` | `{ notification: … }` |
   | `markAllRead()` → `MessageResponse` | `{ updated: n }` |
   | `latestNdvi()` → `NdviDto` | `{ ndvi: … }` |
   | deletion create/get/cancel → `DeletionRequestDto` | `{ request: … }` |
   | `listNdvi()`, `myListings()`, `listNotifications()` → `ItemsResponse` | paginated `{ items, page, … }` |

   Fix by aligning the DTOs/wrappers on one side (server envelope is the source of truth
   in [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) and `backend/openapi.yaml`).

---

## 4. Documentation & deliverables (`docs/`)

| # | Deliverable | Status |
|---|-------------|--------|
| — | `00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md` | ✅ |
| — | `01-DESIGN-SYSTEM.md` | ✅ |
| — | `02-API-CONTRACT.md` | ✅ |
| — | `03-DATA-RETENTION-AND-DELETION.md` | ✅ |
| — | `04-TRACEABILITY-MATRIX.md` | ✅ |
| 7 | `05-TEST-REPORT.md` | ❌ **Missing** (README + `docs/08` link to it) |
| — | `06-STORE-READINESS-CHECKLIST.md` | ✅ |
| — | `07-EXTERNAL-CREDENTIALS.md` | ✅ |
| — | `08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md` | ✅ |
| 11 | `09-DEPLOYMENT.md` | ❌ **Missing** (README links to it) |
| 12 | `privacy-policy.md` | ✅ |
| 13 | `terms-of-service.md` | ❌ **Missing** (README links to it) |
| 14 | `account-deletion.html` | ❌ **Missing** (README links to it) |
| 3 | `backend/openapi.yaml` | ✅ **Added** |

The four missing files are linked from the README deliverable index → **broken links**.

---

## 5. Repository & DevOps

| Item | Status | Notes |
|------|--------|-------|
| `README.md` | ✅ | Deliverable index (4 dead links), quick-start |
| `LICENSE` (MIT) | ✅ | Added |
| `CONTRIBUTING.md` | ✅ | Added |
| GitHub Actions CI | ✅ | `.github/workflows/ci.yml` — backend tests + Android build/test |
| `.gitattributes` | ❌ | No line-ending normalisation |
| `package-lock.json` | ❌ | Dependency versions unpinned |
| Gradle wrapper | ❌ | See §3.2 |
| Secrets hygiene | ✅ | `.gitignore` covers `.env`, keystores, `local.properties` |

---

## 6. Suggested next steps (priority order)

1. **Make the Android app runnable** *(blocking everything mobile)*
   - Add `AgriConnectApp` (`@HiltAndroidApp`) and `MainActivity` (`@AndroidEntryPoint`).
   - Add Hilt modules providing Room, Retrofit/`ApiService`, `SessionManager`, sync deps.
   - Add a minimal `NavHost` + a login and dashboard screen to prove the wiring.
2. **Build the repository layer** and reconcile the client/server envelope & path
   mismatches in §3.3 (fix `POST /devices`, unwrap `{ user }`/`{ request }`/`{ ndvi }`).
3. **Fix release build**: create `proguard-rules.pro` (or drop it from `proguardFiles`).
4. **Add the Gradle wrapper** (`gradle wrapper --gradle-version 8.9`) so `./gradlew` works
   and CI can use it.
5. **Write the missing docs**: `05-TEST-REPORT.md`, `09-DEPLOYMENT.md`,
   `terms-of-service.md`, `account-deletion.html`; fix the README links.
6. **Add backend integration tests** (`tests/api/`) against a real Postgres.
7. **Add `package-lock.json`, an ESLint config, and `.gitattributes`.**
8. **Reconcile the API contract** with the three undocumented endpoints, then keep
   `openapi.yaml`, `docs/02-API-CONTRACT.md` and `ApiService.kt` in sync.
9. **Wire real external providers** (SMS/email, Earth Engine, push) per
   [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md).

---

## 7. How to verify what exists

### Backend
```bash
cd backend
cp .env.example .env          # fill in DB + secrets
npm install
npm run migrate
npm test                      # runs tests/unit/* only
npm start                     # http://localhost:4000  ·  docs at /api-docs
```

### Android
```bash
cd android
./gradlew :app:assembleDebug   # ⚠️ no wrapper committed — use a system Gradle 8.9+ for now
./gradlew :app:testDebugUnitTest
```
> Expect the current tree to require the §3.2 additions before the app launches.

---

## 8. Contract of honesty

This document is deliberately pessimistic: unexecuted work is marked 🟡 even when code
exists, and missing files are called out even where the README implies they are present.
If you implement or verify something, **update this file** (and, where relevant,
[`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md)).
Do not mark anything ✅ without running it.
