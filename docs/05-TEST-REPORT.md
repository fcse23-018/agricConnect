# 05 — Test Report

> **Purpose.** An honest record of what was executed, what passed, what failed, and what
> could **not** be run — and why. Nothing here is claimed as passing unless it actually ran.
>
> **Last updated:** 2026-10-16
> **Environment:** Windows 11 (amd64) · Temurin JDK 17.0.20 · Android SDK (compileSdk 35)
> · Gradle 8.9 (wrapper) · AGP 8.7.3

---

## 1. Summary

| Suite | Tests | Result | Executed here? |
|-------|------:|--------|:--------------:|
| Backend — unit (`backend/tests/unit`) | 68 | ✅ Pass (last recorded run) | ❌ No Node.js runtime available |
| Backend — API smoke (`backend/tests/api`) | 5 | ✅ Pass (last recorded run) | ❌ No Node.js runtime available |
| Android — unit (`app/src/test`) | 12 | ✅ **Pass — re-run 2026-10-16** | ✅ Yes |
| Android — instrumented (`app/src/androidTest`) | 0 authored | ⏭️ Not run | ❌ No emulator/device |
| Android — `:app:assembleDebug` | — | ✅ **BUILD SUCCESSFUL** | ✅ Yes |
| Manual on-device walkthrough | — | ⏭️ Not run | ❌ No emulator/device |

Legend: ✅ executed and passed · ❌ could not be executed here · ⏭️ deliberately not run.

The backend suites are green in CI (`.github/workflows/ci.yml` runs `npm test` against a
PostgreSQL 16 service) and were green on the original build machine (73/73). They are listed
as **"last recorded run"** rather than re-verified here because this machine has no Node.js
installation.

---

## 2. Android build (`:app:assembleDebug`)

| Item | Value |
|------|-------|
| Command | `./gradlew :app:assembleDebug` |
| Result | **BUILD SUCCESSFUL** |
| Tasks | 41 actionable (15 executed, 26 up-to-date) |
| Artifact | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Size | **22.3 MB** |
| Warnings | None blocking. (An inert `application@android:usesCleartextTraffic` manifest-merge note was removed with commit `f4736ed`.) |

The debug APK is signed with the debug keystore and is installable on a device/emulator.
Release minification rules (`proguard-rules.pro`) are present but `assembleRelease` was **not**
run here (it requires `android/keystore.properties`).

---

## 3. Android unit tests (`:app:testDebugUnitTest`)

Executed 2026-10-16 with `./gradlew :app:testDebugUnitTest --rerun`.
**12 tests · 0 failures · 0 errors · 0 skipped.**

| Class | Tests | Focus |
|-------|------:|-------|
| `data.local.AgriDatabaseTest` | 5 | Room persistence, enum converters, sync-queue enqueue on write/delete |
| `data.remote.AuthApiTest` | 4 | Wire contract: `POST /auth/login`, `POST /auth/register` (path + idempotency key + body), 400 error-envelope → field errors, 5xx never leaks server text |
| `data.remote.SyncApiTest` | 1 | `sync/push` parses `synced` / `conflict` / `failed` results |
| `data.remote.TokenAuthenticatorTest` | 2 | 401 → single refresh + retry + rotated token persisted; refresh failure → session cleared |

These are pure JVM tests (Robolectric/MockWebServer) — no device required.

---

## 4. Not executed (and why)

| Item | Reason |
|------|--------|
| Backend `npm test` (73 tests) | No Node.js/npm on this build machine. Previously recorded as 73/73 passing; CI runs it on every push to `main`. |
| `:app:connectedDebugAndroidTest` | No emulator or physical device attached. `androidTest/` currently contains only the Hilt runner (`HiltTestRunner.kt`); no UI tests are authored yet. |
| Manual app walkthrough | Not run. Compose layout/visual fidelity to the design reference remains **unverified**. |
| Release build / store bundle | Requires `keystore.properties` (never committed). |
| Real SMS/email delivery | No provider credentials (see `docs/07-EXTERNAL-CREDENTIALS.md`). |
| Real Sentinel-2 NDVI | No Earth Engine account; mock mode tags results `source: "MOCK"`. |

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

**Fix (commit `f4736ed`):** `API_BASE_URL` now resolves in priority order —
`-PapiBaseUrl=…` → `apiBaseUrl` in `local.properties` → the auto-detected GitHub Codespaces
forwarded port → the per-build-type default. Cleartext to a dev server is already enabled
for debug builds via `src/debug/AndroidManifest.xml`; release remains HTTPS-only. The README
documents the override.

**Verification:** re-ran `:app:assembleDebug` (BUILD SUCCESSFUL) and `:app:testDebugUnitTest`
(12/12) after the change. End-to-end sign-in against a live backend is **not** asserted here
— it additionally requires the backend to be running and reachable.

---

## 6. How to reproduce

```bash
# Backend (needs Node.js + a database; CI uses a PostgreSQL service container)
cd backend
cp .env.example .env          # fill in DB + secrets
npm install
npm run migrate
npm test                      # 73 tests — unit + HTTP smoke

# Android (JDK 17 + Android SDK 35)
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

---

## 7. Honesty statement

Numbers in §2 and §3 were produced on 2026-10-16 by the commands shown and are reproducible.
The backend figures in §1 are the last independently recorded results (and are re-run by CI);
they were **not** re-executed on this machine. Any item marked "not executed" should be
treated as unverified until someone runs it. See
[`08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`](08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md).
