# Deployment Guide

This guide covers taking AgriConnect from a clean checkout to a running backend and
a signed Android release. It is written to match **what the code actually does**; where a
step depends on an external service that has not been supplied, it says so explicitly
(see [`07-EXTERNAL-CREDENTIALS.md`](07-EXTERNAL-CREDENTIALS.md)).

> **Nothing in this document has been executed against production infrastructure.**
> It is a runbook, not a record of a past deployment.

---

## 0. What you are deploying

| Component | Technology | Where it runs |
|-----------|------------|---------------|
| API service | Node.js 18.18+ / Express 4, ESM | Container / VM behind TLS |
| Database | PostgreSQL 15+ | Managed instance or container |
| Android app | Kotlin 2.0 · Jetpack Compose · AGP 8.7 | Google Play (internal track first) |

Two independent pipelines. The Android app talks to the API at
`BuildConfig.API_BASE_URL` (release default `https://api.agriconnect.bw/api/v1/`).

---

## 1. Backend

### 1.1 Prerequisites

- Node.js **≥ 18.18** and npm.
- A reachable PostgreSQL **15+** database and a role that can create tables.
- A generated pair of JWT secrets (access and refresh **must differ**):

  ```bash
  node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
  ```

### 1.2 Configure

```bash
cd backend
cp .env.example .env
```

Then edit `.env`. The values that **must** change for any non-local environment:

| Variable | Production value |
|----------|------------------|
| `NODE_ENV` | `production` |
| `PG*` / `DATABASE_URL` | Real database credentials |
| `PGSSLMODE` | `require` when the database is not on localhost |
| `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` | Two different 48-byte random strings |
| `PUBLIC_BASE_URL` | `https://api.agriconnect.bw` |
| `CORS_ORIGINS` | Only the origins that must call the API |
| `DELIVERY_PROVIDER` | `SMTP` or `TWILIO` (**not** `NONE`/`OUTBOX`) |
| `EXPOSE_DEV_CODES` | `false` |
| `GEE_ENABLED` | `true` once Earth Engine credentials exist (otherwise leave `false`) |

> The service is written to **fail fast in production** when a development-only
> default is left in place (e.g. `DELIVERY_PROVIDER=NONE`, `EXPOSE_DEV_CODES=true`,
> missing JWT secrets). Fix the reported variable and restart.

### 1.3 Migrate and (optionally) seed

```bash
npm install --no-audit --no-fund   # install first time
npm run migrate                    # applies migrations/*.sql in order
npm run seed                       # DEVELOPMENT ONLY — sample data
```

- Migrations are ordered and transactional; **never** run `npm run migrate:down` against
  production without a backup.
- `npm run seed` writes demo accounts and must not be run in production.

### 1.4 Run

```bash
npm start          # production
npm run dev        # local, restarts on file change
```

The server listens on `PORT` (default `4000`). Verify:

```bash
curl -fsS "$PUBLIC_BASE_URL/health"      # 200 + { status: "ok", ... }
curl -fsS "$PUBLIC_BASE_URL/api/v1/version"
```

`/health` is intentionally **outside** `/api/v1` so load balancers can probe a stable path.

### 1.5 Container

A `Dockerfile` and `docker-compose.yml` are provided for local/self-hosted use.
Compose starts the API and a PostgreSQL instance together; it reads the same `.env`.
For a real deployment put the container behind a TLS-terminating proxy (nginx, Caddy,
or a cloud load balancer) and terminate HTTPS there.

Reverse proxy requirements:

- Forward `X-Forwarded-For` / `X-Forwarded-Proto` (the app trusts the proxy hop for
  rate limiting and secure-cookie decisions).
- Allow request bodies up to `MAX_UPLOAD_BYTES` (default 2 MiB) — avatar/listing uploads.
- Proxy `/health` and `/api/v1/*` to the container port; do not expose PostgreSQL.

### 1.6 Operational checklist

- [ ] `NODE_ENV=production`, `EXPOSE_DEV_CODES=false`, real `DELIVERY_PROVIDER`.
- [ ] TLS certificate valid for the API hostname; HTTP redirects to HTTPS.
- [ ] Database backups scheduled (point-in-time recovery preferred) — deletion
      requests depend on being able to *prove* what was removed.
- [ ] Secret material lives in a secret manager, never in the image or the repo.
- [ ] `UPLOAD_DIR` is a persistent volume (or move to object storage).
- [ ] Logs ship to a sink with a retention window; they must not contain tokens
      (the app redacts credentials, but verify at the proxy too).
- [ ] Uptime monitor on `/health`; error monitor configured (disabled by default in-app).

---

## 2. Android

### 2.1 Prerequisites

- JDK **17** (the project targets `JavaVersion.VERSION_17`; a Temurin 17 or 21 JDK
  works for building).
- Android SDK with **platform 35** and **build-tools 35.0.0**.
- The Gradle wrapper is committed, so no system Gradle is required:

  ```bash
  cd android
  ./gradlew --version        # downloads Gradle 8.9 on first use
  ```

### 2.2 Debug build

```bash
cd android
./gradlew :app:assembleDebug
```

The debug build points at `http://10.0.2.2:4000/api/v1/` (the host machine as seen
from an emulator) and enables verbose, **credential-redacting** HTTP logging.

### 2.3 Release signing

Signing is opt-in and never committed. Create `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/agriconnect-release.jks
storePassword=…
keyAlias=agriconnect
keyPassword=…
```

Create the keystore once (keep it and its passwords in a safe, backed-up location —
losing it means you can never update the app on Play):

```bash
keytool -genkeypair -v -keystore agriconnect-release.jks \
  -alias agriconnect -keyalg RSA -keysize 2048 -validity 10000
```

Then:

```bash
./gradlew :app:bundleRelease     # AAB for Play
./gradlew :app:assembleRelease   # APK (internal distribution)
```

If `keystore.properties` is absent the release build still **compiles** (producing an
unsigned artefact) so CI can verify it, but it cannot be uploaded.

### 2.4 Pointing at a different backend

`API_BASE_URL` is a `buildConfigField` in `app/build.gradle.kts`. To target a staging
host, change the `release` value (or add a `staging` build type) — do **not** hard-code
a URL anywhere else. Release builds require HTTPS; cleartext is permitted only in the
debug variant.

### 2.5 Release build checks

- [ ] R8 enabled (`isMinifyEnabled = true`, `isShrinkResources = true`) and the app
      launches — the keep rules live in `app/proguard-rules.pro`.
- [ ] `versionCode` incremented and `versionName` updated.
- [ ] Release `API_BASE_URL` points at production HTTPS.
- [ ] Install the release APK/AAB on a physical device and exercise sign-in, an
      offline write, and a sync when connectivity returns.

---

## 3. CI

`.github/workflows/ci.yml` runs two jobs on every push/PR to `main`:

1. **Backend** — installs dependencies against a PostgreSQL service container, applies
   migrations, runs `npm test`.
2. **Android** — assembles the debug APK and runs the JVM unit tests.

The workflow is a *build gate*, not a deployment pipeline. Publishing to Play and
rolling the backend are manual, deliberate steps.

---

## 4. Rollback

| Component | Rollback |
|-----------|----------|
| Backend | Redeploy the previous image tag. Migrations are forward-only; if a release added a migration, the previous image may still run against the newer schema (verify before rolling back). |
| Android | Halt the Play staged rollout. Android does **not** allow reinstalling an older `versionCode` for existing users except via a new, higher `versionCode`; fix forward. |

---

## 5. First-run smoke test (after any deploy)

1. `GET /health` returns 200.
2. Register a new account; a **farmer** is created (never a privileged flag from the client).
3. Sign in from the Android app; the dashboard shows the account name and role.
4. Turn off connectivity, create a record, confirm the offline banner; restore
   connectivity and confirm the pending counter drains.
5. Request a password reset and receive it through the configured provider.
6. Submit an account-deletion request; confirm the grace-period state is reflected
   in the API and in the hosted `account-deletion.html` flow.
