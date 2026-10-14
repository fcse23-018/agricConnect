# AgriConnect

**Offline-first farm management, satellite crop insights and produce marketplace for
smallholder farmers in Botswana.**

An Android application (Kotlin · Jetpack Compose · Room · WorkManager · Retrofit) backed by a
Node.js/Express REST service and PostgreSQL, with Google Earth Engine / Sentinel-2 vegetation
indices and a buyer marketplace.

---

## Repository layout

```
agriConnect/
├─ android/                     Native Android app (Kotlin, Compose, Material 3)
│  ├─ app/src/main/java/bw/agriconnect/
│  │  ├─ domain/                Pure domain models and enums
│  │  ├─ data/local/            Room: entities, DAOs, database, converters
│  │  ├─ data/remote/           Retrofit API, DTOs, interceptors
│  │  ├─ data/session/          Encrypted token storage + session state
│  │  ├─ data/repository/       Repository layer
│  │  ├─ data/sync/             WorkManager sync engine + conflict resolution
│  │  ├─ di/                    Hilt modules
│  │  └─ ui/                    Theme, components, navigation, screens
│  └─ app/src/test, androidTest Automated tests
├─ backend/                     Node.js/Express service
│  ├─ src/modules/              auth, users, farms, farm, finance, satellite,
│  │                            marketplace, orders, notifications, sync, public
│  ├─ src/services/             delivery, ndvi/gee, notifications, audit
│  ├─ src/middleware/           auth, roles, validation, rate limiting, idempotency
│  ├─ migrations/               PostgreSQL schema (ordered, transactional)
│  ├─ tests/                    Unit + API integration tests
│  └─ openapi.yaml              Machine-readable API contract
└─ docs/                        All written deliverables (see below)
```

---

## Deliverables index

| # | Deliverable | Location |
|---|-------------|----------|
| 1 | Requirements summary, conflicts & gaps | [`docs/00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md`](docs/00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md) |
| 2 | Design system & tokens | [`docs/01-DESIGN-SYSTEM.md`](docs/01-DESIGN-SYSTEM.md) |
| 3 | API contract | [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) · [`backend/openapi.yaml`](backend/openapi.yaml) |
| 4 | Data model & migrations | [`backend/migrations/`](backend/migrations) |
| 5 | Data retention & deletion policy | [`docs/03-DATA-RETENTION-AND-DELETION.md`](docs/03-DATA-RETENTION-AND-DELETION.md) |
| 6 | Traceability matrix | [`docs/04-TRACEABILITY-MATRIX.md`](docs/04-TRACEABILITY-MATRIX.md) |
| 7 | Test report (executed / passed / failed / unrunnable) | [`docs/05-TEST-REPORT.md`](docs/05-TEST-REPORT.md) |
| 8 | Store readiness checklist | [`docs/06-STORE-READINESS-CHECKLIST.md`](docs/06-STORE-READINESS-CHECKLIST.md) |
| 9 | External credentials & services to provide | [`docs/07-EXTERNAL-CREDENTIALS.md`](docs/07-EXTERNAL-CREDENTIALS.md) |
| 10 | Known limitations & unverified items | [`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md) |
| 11 | Deployment guide | [`docs/09-DEPLOYMENT.md`](docs/09-DEPLOYMENT.md) |
| 12 | Privacy policy | [`docs/privacy-policy.md`](docs/privacy-policy.md) |
| 13 | Terms of service | [`docs/terms-of-service.md`](docs/terms-of-service.md) |
| 14 | External account-deletion page | [`docs/account-deletion.html`](docs/account-deletion.html) |
| 15 | Reusable UI components & design tokens | [`android/.../ui/theme`](android/app/src/main/java/bw/agriconnect/ui/theme) · [`ui/components`](android/app/src/main/java/bw/agriconnect/ui/components) |
| 16 | Offline-first sync engine | [`android/.../data/sync`](android/app/src/main/java/bw/agriconnect/data/sync) · [`backend/src/modules/sync`](backend/src/modules/sync) |
| 17 | Role-based access control (3 layers) | [`backend/src/middleware/roles.js`](backend/src/middleware/roles.js) · [traceability](docs/04-TRACEABILITY-MATRIX.md) |
| 18 | Satellite NDVI pipeline | [`backend/src/services/gee.js`](backend/src/services/gee.js) · [`ndvi.js`](backend/src/services/ndvi.js) |
| 19 | Account lifecycle (register→delete) | [`backend/src/modules/auth`](backend/src/modules/auth) · [`account`](backend/src/modules/account) |

---

## Quick start

### Backend

```bash
cd backend
cp .env.example .env          # then edit secrets/database values
npm install
npm run migrate               # applies migrations/*.sql in order
npm run seed                  # optional development data
npm start                     # http://localhost:4000  (docs at /api-docs)
npm test                      # unit + API tests
```

### Android

```bash
cd android
./gradlew :app:assembleDebug          # debug APK  (points at http://10.0.2.2:4000)
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (needs a device/emulator)
./gradlew :app:bundleRelease          # release bundle (needs keystore.properties)
```

The release build requires `android/keystore.properties` (never committed — see
[`docs/06-STORE-READINESS-CHECKLIST.md`](docs/06-STORE-READINESS-CHECKLIST.md)).

---

## Roles at a glance

| Capability | Farmer | Farm Employee | Buyer |
|------------|:------:|:-------------:|:-----:|
| Harvest & livestock records | ✅ | ✅ | ❌ |
| Sales, expenses, financial summaries | ✅ | ❌ | ❌ |
| Farm plot mapping & NDVI | ✅ | ❌ | ❌ |
| Publish produce listings | ✅ | ❌ | ❌ |
| Browse produce & place orders | ✅ (own) | ❌ | ✅ |
| Manage own profile | ✅ | ✅ | ✅ |

Enforced in the UI, in API middleware, and in database ownership queries. Hiding a control is
never the enforcement point.

---

## Honest status

> **New here?** Read [`PROGRESS.md`](PROGRESS.md) first — a file-by-file map of what is
> implemented, what is stubbed or mocked, and what is still missing.

This repository is built to be reviewable and runnable, but **not every claim is verified**.
[`docs/05-TEST-REPORT.md`](docs/05-TEST-REPORT.md) lists exactly what was executed, what passed,
what failed and what could not be run, and
[`docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](docs/08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md) lists
what still requires real credentials or product decisions (SMS/email provider, Earth Engine account,
map provider sign-off, legal review of retention periods).

Nothing in this repository fabricates a passing test result or a working external integration.
