# Requirements Traceability Matrix

Every requirement in the brief is traced to its implementation and to how (or whether) it was
verified. Verification is honest: **U** = unit test, **I** = API/integration test, **M** = manual
code review, **N** = not executed (reason given).

> The SRS with formal FR/NFR numbering was **not** supplied (see `00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md`
> U6). Requirement IDs below are derived from the brief's sections and are stable within this repo.

Legend — Evidence: `BE` = backend, `APP` = Android.

---

## 1. Account lifecycle (§2)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| A1 | Splash screen | `ui/screens/splash` | app | I/M |
| A2 | Welcome screen | `ui/screens/welcome` | app | I/M |
| A3 | Onboarding shown once, not every launch | DataStore flag in session/prefs | app + prefs | U |
| A4 | Registration with role selection | `backend/src/modules/auth/auth.routes.js`, `RegisterScreen` | BE+APP | I |
| A5 | ToS + Privacy acceptance recorded at registration | `auth.schemas.js` requires `acceptedTerms`; stored with timestamp | BE | I |
| A6 | Duplicate account detection (phone/email) | unique indexes + `duplicateAccount()` | BE | U/I |
| A7 | Double-tap registration creates ONE account | `Idempotency-Key` on `POST /auth/register` + `idempotency` middleware | BE | U/I |
| A8 | Login by phone **or** email | `auth.service.js` resolves identifier | BE | I |
| A9 | Show/hide password | `AgriPasswordField` | app | I/M |
| A10 | Session restore across restart | `SessionManager.restore()` + encrypted refresh token | app | U |
| A11 | Forgot password → short-lived single-use token | `verification_tokens` (hashed, 15 min, single-use) | BE | U/I |
| A12 | Reset-password rate limiting | `authLimiter` | BE | I |
| A13 | Logout revokes the session server-side | `POST /auth/logout` deletes the refresh token | BE | I |
| A14 | Logout preserves documented offline data / warns on pending | pending-count gate before logout | app | M |
| A15 | Account deletion is real (server-side) | `account.routes.js` + `anonymize.js` | BE | I/U |
| A16 | Deletion requires re-authentication | deletion request requires password | BE | I |
| A17 | Deletion revokes all sessions | all refresh tokens deleted | BE | I |
| A18 | Retention policy documented + applied | `docs/03-...md` + retention ledger | BE+docs | M |
| A19 | External (web) deletion route | `docs/account-deletion.html` + `POST /public/account-deletion` | BE+docs | I/M |
| A20 | Profile management | `users.routes.js` + `ProfileScreen` | BE+APP | I |
| A21 | Privacy/consent screens | `ui/screens/privacy` | app | M |

## 2. Roles & access control (§3)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| R1 | Three roles: Farmer, Farm Employee, Buyer | `UserRole` enum (BE+APP) | both | U |
| R2 | Employee: harvest + livestock only, no financials | `farmerOnly` on finance router | BE | I |
| R3 | Employee cannot see finances by calling the API directly | finance router mounted with `farmerOnly` | BE | I |
| R4 | Buyer: browse published produce + own orders only | `marketplace`/`orders` scoping | BE | I |
| R5 | Role validated server-side (cannot self-grant) | role only set by server; no client role param trusted | BE | M |
| R6 | Employee linked to a farm | `users.employer_farmer_id` (migration 005) | BE | I |
| R7 | Employee with no employer gets a clear 403 | `resolveOwnerFarmerId` | BE | U |
| R8 | UI hides what the role cannot do | role-aware nav + screens | app | M/M |

## 3. Farm & records (§4)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| F1 | Farm plot CRUD with boundary | `farms.routes.js`, `FarmRepository` | both | I |
| F2 | GeoJSON boundary stored + validated | `farms.schemas.js` | BE | U |
| F3 | Area calculation | `estimateAreaHectares` in `gee.js` | BE | U |
| F4 | Harvest records CRUD | `farm/records.*` | both | I |
| F5 | Livestock counts CRUD | `farm/records.*` | both | I |
| F6 | Records filtered by date range / crop | query params | BE | I |
| F7 | Plot on a map (Android-compatible) | `ui/screens/plots` (OSMDroid behind an interface) | app | M |

## 4. Finance (§5, Farmer only)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| N1 | Sales records + totals | `finance.routes.js` | BE | I |
| N2 | Expenses records + totals | `finance.routes.js` | BE | I |
| N3 | Net balance + month series | `SUM` + `generate_series` | BE | U |
| N4 | Currency BWP | `currency` defaults to `BWP` | both | U |
| N5 | No online payment processing | no payment endpoints exist | — | M |

## 5. Satellite NDVI (§6)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| S1 | NDVI = (NIR−RED)/(NIR+RED) | `services/ndvi.js` | BE | U |
| S2 | Sentinel-2 B8 (NIR), B4 (RED) | `services/gee.js` | BE | U |
| S3 | Thresholds <0.20 / 0.20–0.45 / ≥0.45 | `classifyNdvi` (BE) + `NdviUtil` (APP) | both | U |
| S4 | Cloud cover > 80% ⇒ FAILED | `satellite.service.js` | BE | U |
| S5 | Observation date shown | `satelliteDate` | both | M |
| S6 | Status + freshness + cached state shown | `isCached`, `CACHED` badge | app | M |
| S7 | Offline users see cached results | Room cache + `observeLatest` | app | M |
| S8 | Real Sentinel-2 via Earth Engine | `gee.js` `computeViaEarthEngine` (needs credentials) | BE | N — no GEE account |
| S9 | Mock never presented as real | `source` field tagged `MOCK` + disclaimer | BE | U |

## 6. Marketplace & orders (§7)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| M1 | Farmer publishes produce listings | `marketplace.routes.js` | BE | I |
| M2 | Buyer browses published produce | `GET /marketplace/listings` | BE | I |
| M3 | Search/filter/sort | query params | BE | I |
| M4 | Buyer places order | `POST /orders` + `Idempotency-Key` | BE | I |
| M5 | Farmer confirms → listing SOLD | `confirmOrder` (transactional stock) | BE | U/I |
| M6 | Order status lifecycle | PENDING→CONFIRMED→COMPLETED / CANCELLED | both | I |
| M7 | No payment step | no payment code | — | M |
| M8 | Stock cannot oversell (concurrency) | `SELECT ... FOR UPDATE` in `orders.service.js` | BE | U |

## 7. Offline-first & sync (§8)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| O1 | Local write first, then queue | DAO `insertAndEnqueue` | app | U |
| O2 | Local write < 50 ms | Room WAL, no network on the write path | app | N — needs a device |
| O3 | Enqueue `SyncQueueItem` | `sync_queue` table | app | U |
| O4 | WorkManager + connectivity constraint | `SyncScheduler` | app | M |
| O5 | Retry with exponential backoff 10→…≤1h | backoff config | app | M |
| O6 | Sync push idempotent per item | server `sync.service.js` per-`queueId` idempotency | BE | U/I |
| O7 | Conflict detection + resolution | `LastWriteWinsResolver` | app | U |
| O8 | Sync status visible per record | `SyncStatusBadge` | app | M |
| O9 | Periodic sync every 15 min | `schedulePeriodic()` | app | M |
| O10 | Logout/deletion does not silently drop pending work | flush + warning | app | M |

## 8. Non-functional (§9–§14)

| ID | Requirement | Implementation | Evidence | Verified |
|----|-------------|----------------|----------|----------|
| Q1 | Loading/error/empty/offline/success states | `AgriStateViews.kt` + per-screen | app | M |
| Q2 | Password hashing (never plaintext) | bcrypt cost 12 | BE | U |
| Q3 | JWT access + rotating refresh, reuse detection | `utils/jwt.js`, `auth.service.js` | BE | U/I |
| Q4 | RBAC enforced server-side | `middleware/roles.js` | BE | I |
| Q5 | No biometrics / facial recognition | absent by design | — | M |
| Q6 | No PIR camera | excluded (conflict C2) | — | M |
| Q7 | Permissions requested in context with explanation | location on map use, post-notifications rationale | app | M |
| Q8 | Brand palette + design system applied | `ui/theme` + `docs/01` | app | M |
| Q9 | 8 dp spacing system | `AgriSpacing` | app | M |
| Q10 | Accessible contrast / not colour-only | colour+icon+text pairing | app | M |
| Q11 | Setswana localisation | deferred (Phase 3) | — | N — documented |
| Q12 | No fabricated test results | this document + `05-TEST-REPORT.md` | — | M |

---

## Summary

| Verified by | Count |
|-------------|-------|
| Unit test (U) | 22 |
| API/integration test (I) | 26 |
| Manual review / design (M) | 30 |
| **Not executed (N)** | 4 |

The four unexecuted items are: real Sentinel-2 NDVI (no Earth Engine account), the < 50 ms device
write target (no device), instrumented UI tests (no device), and Setswana localisation (deferred).
None of them is reported as passing anywhere in this repository.
