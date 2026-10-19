# 11 — System Inventory & Status (Phase 1)

> **Purpose.** Phase 1 of the build brief: extract the architecture documents into a single
> as-built inventory and an honest **working / incomplete / missing** checklist. This file
> describes **what actually exists in the repository today**, not what was planned. Where the
> delivered shape differs from the architecture documents, the difference is stated.
>
> **Sources read:** `AgriConnect_Architecture_UML_Suite.docx` (v1.0),
> `AgriConnect_Architecture_UML_Suite_v1.1.docx` (authoritative),
> `AgriConnect_Coding_Implementation_Plan (draft).docx`,
> `Open Requirements requiring confirmation.docx`, plus the code itself.
>
> **Last updated:** 2026-10-19 · **HEAD:** `ef16184`

---

## 1. As-built technology stack

| Layer | Planned (UML v1.1 §3) | As built | Where |
|-------|-----------------------|----------|-------|
| Mobile UI | Kotlin, Jetpack Compose & ViewBinding | Kotlin + Jetpack Compose, **Material 3** (ViewBinding not used) | `android/app/src/main/java/bw/agriconnect/ui/` |
| Local storage | Room ORM over SQLite | Room **v2**, 10 entities + 10 DAOs | `.../data/local/` |
| Background sync | WorkManager + ConnectivityManager | `SyncWorker` (WorkManager) + `SyncScheduler` + `SyncEngine`/`SyncQueue`/`SyncApplier`/`ConflictResolver` | `.../data/sync/` |
| API client | Retrofit 2 / OkHttp 4, JWT, HTTPS | Retrofit 2 + OkHttp 4, `AuthInterceptor`, `TokenAuthenticator` (silent refresh) | `.../data/remote/` |
| Cloud service | Node.js + Express (RBAC, validation, conflict resolution) | Node.js + Express, zod validation, RBAC middleware, `LastWriteWinsResolver` | `backend/src/` |
| Central DB | PostgreSQL 15 | PostgreSQL (Supabase), 16 tables, 6 migrations | `backend/migrations/`, `backend/db/` |
| Satellite | Google Earth Engine / Sentinel-2 | Two paths: Express `/satellite` NDVI (mock when GEE unconfigured) **and** a standalone FastAPI `crop-api/` (mock by default) | `backend/src/services/gee.js`, `crop-api/` |
| Mapping | Android-compatible map + GeoJSON | GeoJSON boundary capture (GPS corners / manual lat-lng); **OSMDroid is declared as a dependency but no `MapView` is wired into any screen** | `.../core/util/PlotBoundary.kt`, `ui/components/BoundaryEditor.kt` |
| Hosting | (not fixed in UML) | Render (Node API) + Supabase (DB + Storage) | `render.yaml` |
| Image storage | (not fixed in UML) | Supabase Storage bucket `agriconnect-uploads`, `POST /uploads` → `/uploads/:name` 302 → public URL | `backend/src/services/uploads.js` |

**Deliberate divergence — no Redis.** No caching/queue Redis is used anywhere (no Node package, no
`REDIS_URL`, no Render Redis service). Rate limiting and idempotency are database/in-memory backed
(see `08` T6/T7).

---

## 2. Backend module inventory

| Module | Mount prefix | Responsibility |
|--------|--------------|----------------|
| `auth` | `/api/v1/auth` | register, login, refresh, logout, password reset, verification |
| `users` | `/api/v1/users` | profile, avatar upload, employer link, notification prefs, password change, data export |
| `account` | `/api/v1` | account-deletion request / status / cancel |
| `farms` | `/api/v1/farms` | farm-plot CRUD (GeoJSON boundary) |
| `farm` (records) | `/api/v1/farm` | harvest + livestock records |
| `finance` | `/api/v1/finance` | sales, expenses, summary (owner only) |
| `satellite` | `/api/v1/satellite` | NDVI generate / history / latest / by-id (+ advice) |
| `uploads` | `/api/v1/uploads` | generic image upload |
| `marketplace` | `/api/v1/marketplace` | listings CRUD, publish, archive |
| `orders` | `/api/v1/orders` | place, list, confirm, reject, complete, cancel |
| `notifications` | `/api/v1/notifications` | in-app feed, read, read-all, device tokens |
| `sync` | `/api/v1/sync` | pull + push (batch, conflict-aware) |
| `public` | `/api/v1/public` | unauthenticated account-deletion route (store requirement) |

**Services** (`backend/src/services/`): `gee.js`, `ndvi.js`, `ndviAdvice.js`, `notifications.js`,
`uploads.js`, `delivery.js`, `audit.js`. **Middleware** (`backend/src/middleware/`): `auth`, `roles`,
`errorHandler` (zod → envelope), `idempotency`, `rateLimit`, `requestId`.

---

## 3. Endpoint inventory

**73 Express routes** under `/api/v1` (plus `/api-docs` and `/health`). Full list (file · method · path):

```
account      POST   /users/me/deletion-request        account   GET   /users/me/deletion-request
account      POST   /users/me/deletion-request/cancel auth      POST  /login
auth         POST   /logout                           auth      POST  /logout-all
auth         GET    /me                               auth      POST  /password/forgot
auth         POST   /password/reset                   auth      POST  /password/verify
auth         POST   /refresh                          auth      POST  /register
auth         POST   /verification/confirm             auth      POST  /verification/request
farms        GET    /plots                            farms     POST  /plots
farms        GET    /plots/:id                        farms     PATCH /plots/:id
farms        DELETE /plots/:id                        finance   GET   /expenses
finance      POST   /expenses                         finance   GET   /expenses/:id
finance      PATCH  /expenses/:id                     finance   DELETE /expenses/:id
finance      GET    /sales                            finance   POST  /sales
finance      GET    /sales/:id                        finance   PATCH /sales/:id
finance      DELETE /sales/:id                        finance   GET   /summary
marketplace  GET    /listings                         marketplace POST /listings
marketplace  GET    /listings/:id                     marketplace PATCH /listings/:id
marketplace  POST   /listings/:id/archive             marketplace POST /listings/:id/publish
marketplace  GET    /my-listings                      notifications GET /  ·  PATCH /:id/read
notifications POST  /devices                          notifications POST /read-all
orders       POST   /                                 orders    GET   /
orders       GET    /:id                              orders    POST  /:id/cancel
orders       POST   /:id/complete                     orders    POST  /:id/confirm
orders       POST   /:id/reject                       public    POST  /account-deletion
records      GET    /harvests                         records   POST  /harvests
records      GET    /harvests/:id                     records   PATCH /harvests/:id
records      DELETE /harvests/:id                     records   GET   /livestock
records      POST   /livestock                        records   GET   /livestock/:id
records      PATCH  /livestock/:id                    records   DELETE /livestock/:id
satellite    POST   /ndvi                             satellite GET   /ndvi
satellite    GET    /ndvi/:id                         satellite GET   /ndvi/latest
sync         GET    /pull                             sync      POST  /push
uploads      POST   /                                 users     GET   /me
users        PATCH  /me                               users     POST  /me/avatar
users        POST   /me/employer-link                 users     GET   /me/export
users        PUT    /me/notification-preferences      users     POST  /me/password
```

**FastAPI `crop-api/` — 4 endpoints** (standalone; not yet called by the app):

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | liveness (no API key) |
| POST | `/v1/area` | measured area + min pixel count + GeoJSON ring |
| POST | `/v1/indices` | full NDVI/EVI series for a marked area |
| POST | `/v1/health-summary` | latest reading + ~2-week change + status |

---

## 4. Database schema inventory

6 migrations, **16 tables**:

| Migration | Tables created |
|-----------|----------------|
| `001_init.sql` | `users`, `refresh_tokens`, `verification_tokens`, `idempotency_keys` |
| `002_domain.sql` | `farm_plots`, `harvest_records`, `livestock_counts`, `sale_transactions`, `expenses`, `satellite_ndvi` |
| `003_marketplace.sql` | `produce_listings`, `orders` |
| `004_sync_notifications_retention.sql` | `notifications`, `device_tokens`, `sync_queue_items`, `account_deletion_requests`, `retention_ledger`, `audit_log`, `message_outbox` |
| `005_employee_link.sql` | (alters `users` — employer link) |
| `006_role_rename.sql` | (renames `UserRole` values; `FARMER`→`FARM_OWNER`, `FARM_EMPLOYEE`→`FARMER`) |

Supabase Storage bucket `agriconnect-uploads` is created by SQL (`backend/supabase-update.sql`), not
by a migration.

---

## 5. Android surface inventory

- **27 screens** (`ui/**/*Screen.kt`): Splash, Welcome, Onboarding, Login, Register, ForgotPassword,
  Dashboard, Farm, FarmDetail, Harvests, Livestock, Satellite, Finance, Marketplace, ListingDetail,
  CreateListing, Orders, OrderDetail, Notifications, Profile, Settings, NotificationPrefs, Privacy,
  AccountDeletion, OfflineSync, Help, ServerSettings.
- **30 route constants** in `ui/navigation/Routes.kt`, incl. tab routes and pushed detail routes.
- **Role-based navigation** (`AgriNavItems.kt`) — no tab may 403; `more` is in every role because it
  is the only path to profile/settings/sign-out:

  | Role | Tabs |
  |------|------|
  | `FARM_OWNER` | home · farms · satellite · market · more |
  | `FARMER` | home · farms · market · orders · more |
  | `BUYER` | market · orders · alerts · more |

  `homeRouteFor`: `BUYER → market`, else `dashboard`.
- **Data layer:** Room v2, 10 entities + 10 DAOs (FarmPlot, HarvestRecord, LivestockCount, Expense,
  SaleTransaction, ProduceListing, Order, Notification, SatelliteNdvi, SyncQueue).
- **Repositories (11):** Auth, Account, FarmPlot, Harvest, Livestock, Finance, Marketplace, Order,
  Notification, Satellite, Media (+ `RepositoryErrors`, `RepositorySupport` helpers).
- **Unit tests:** 9 classes, 50 tests (see `05-TEST-REPORT.md`).

---

## 6. Working / incomplete / missing checklist

Legend — **✅ verified here** = executed on this machine; **🟡 built, not device-verified** = code +
unit/wire tests exist but no device run; **🔴 incomplete**; **⛔ missing/out of scope**.

### 6.1 ✅ Implemented and verified (automated, this machine)

| Item | Evidence |
|------|----------|
| Backend REST API — all 73 routes | 152/152 backend tests, ESLint 0 errors |
| Domain + auth + RBAC + retention + sync logic | `backend/tests/unit/*` (8 suites) |
| API smoke + Supabase upload round trip | `backend/tests/api/*` (2 suites) |
| crop-api FastAPI service | 34/34 pytest (mock GEE) |
| Android data/domain/remote layers | 50/50 Android unit tests |
| Live deployment | Render `/health` 200, `/version` 200, `/api-docs` 200, protected routes 401 |
| Live upload pipeline | `201 → /uploads/:name 302 → Supabase public URL`, 14 449-byte PNG |
| Live role rename | `FARM_OWNER`/`FARMER` accepted, `FARM_EMPLOYEE` → 400 |

### 6.2 🟡 Built and unit/contract-tested — not yet exercised on a device

| Item | Gap |
|------|-----|
| All 27 Compose screens | No instrumented UI test, no manual device walkthrough |
| Offline queue → sync push/pull | Logic unit-tested; end-to-end device sync not run |
| Image upload → store → render (listing, plot, avatar) | Wire-tested; not run on a device |
| Plot boundary capture (GPS/manual) → NDVI request | Wire-tested; not run on a device |
| NDVI advice card display | Builder + wire contract tested; never rendered on a device |
| Server-URL override (Settings → Server) | Logic tested; never pointed at a live host from the app |
| Role-aware navigation hides forbidden tabs | Reviewed; not tapped through on a device |

### 6.3 🔴 Incomplete (partial)

| Item | State |
|------|-------|
| Conflict resolution UI | `CONFLICT` badge + review affordance exist; full merge screen is minimal (`08` T5) |
| Retention/deletion scheduler | `processDueDeletions()` exists; no cron/queue in production (`08` T1) |
| Email verification enforcement | Codes issued; account usable before verification (`08` T2, U7) |
| crop-api in-app integration | The service exists but the app still reads NDVI via Express `/satellite` (`08` T3b) |
| Offline map tiles | No map engine; no region pre-download (`08` T4) |
| Shared rate-limit / idempotency store | Per-instance in-memory + DB; needs a shared store for multi-instance (`08` T6/T7) |
| Instrumented test suite | `androidTest/` holds only `HiltTestRunner`; zero UI tests |

### 6.4 ⛔ Missing / out of scope (per the brief)

| Item | Why |
|------|-----|
| Online payments | Brief §13 forbids without separate approval (`08` L1) |
| Biometric / facial login | Removed by UML v1.1 (`08` L2) |
| PIR motion camera | Excluded by v1.1 (`08` L3) |
| iOS app | Native Android only (`08` L4) |
| Real SMS/email delivery | No provider configured (`08` L5, U1) |
| FCM push notifications | No Firebase project (`08` L6) |
| Real Sentinel-2 NDVI | No Earth Engine account; results tagged `MOCK` (`08` L7) |
| Setswana localisation | Phase-3 item (`08` L8) |
| Formal SRS (FR/NFR IDs) | Not supplied — traceability IDs are derived from the brief (`04`, `00` U6) |

---

## 7. Traceability to the UML artifacts

The UML suite defines 8 diagrams with assigned owners and SRS IDs (v1.1 §8). Coverage in code:

| UML artifact | SRS IDs (v1.1) | Implementation |
|--------------|----------------|----------------|
| Use Case Diagram | FR-01..03, 08, 15 | role-aware nav + RBAC middleware; offline execution in Room/WorkManager |
| Class Diagram | FR-04..06, 12, NFR-01 | 10 Room entities + 16 Postgres tables; universal sync status |
| Component Diagram | NFR-02, 03, FR-22 | decoupled UI/sync; `ApiService` abstraction |
| Deployment Diagram | NFR-04, 05, FR-35 | Render + Supabase; HTTPS; encrypted token store |
| Sequence 1 (Sync) | FR-10, 11, 13, 14 | `SyncEngine` + `SyncWorker` + `ConflictResolver`; `/sync/push\|pull` |
| Sequence 2 (NDVI) | FR-20, 21, 23 | boundary capture → `/satellite/ndvi` → cached `satellite_ndvi` + advice |
| Activity 1 (Boundary) | FR-30..32 | `PlotBoundary` + add/edit-plot form |
| Activity 2 (Ledger) | FR-33, 34, 36 | finance/sales/expenses + sync branching |
| State Machine 1 (Listing) | FR-40..42 | `ListingStatus` enum + publish/archive routes |
| State Machine 2 (NDVI) | FR-24..26 | `NdviProcessingStatus` + `classifyNdvi` (>80% cloud → FAILED) |

The dated per-requirement matrix (A1..A21, R1..R8, F1..F7, …) lives in `04-TRACEABILITY-MATRIX.md`.

---

## 8. Master-prompt phase map

| Phase | Deliverable | Status |
|-------|-------------|--------|
| **1** | UML extraction + tech/endpoint/module inventory + working/incomplete/missing checklist | **This document** |
| 2 | (next) | not started |

See `PROGRESS.md` for the project-level status board and `05-TEST-REPORT.md` for execution evidence.
