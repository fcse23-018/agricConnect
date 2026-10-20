# Android Release Workflow — APK Version Checking & Updates

This document describes the **release process** for the AgriConnect Android
app, the **version manifest** format, the **client-side update logic**, and
the **security requirements** that must be followed on every release.

---

## 1. Overview

- The Android app checks for updates on **cold start** and **periodically**
  (every 6 hours) when online.
- The check hits a **public, unauthenticated** endpoint:
  `GET /api/v1/app/version` (served by the backend).
- The manifest is a **JSON file** maintained in the backend repo
  (`backend/version-manifest.json`) and deployed with the API.
- **Uploading an APK does not update the manifest** — the manifest must be
  edited manually as part of the release.
- The same `applicationId` (`bw.agriconnect`) and a **compatible signing key**
  are required for every release (upgrade must preserve user data).

---

## 2. Manifest format (`backend/version-manifest.json`)

```json
{
  "published": true,
  "latestVersionName": "1.1.1",
  "latestVersionCode": 3,
  "minSupportedVersionCode": 2,
  "downloadUrl": "https://example.com/agriconnect-v1.1.1.apk",
  "releaseNotes": "Fixed satellite boundary editor; added weather history.",
  "releaseDate": "2026-07-20",
  "severity": "OPTIONAL"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `published` | `boolean` | **yes** | `false` → "no update available" (safe default). Prevents accidental leaks of draft URLs. |
| `latestVersionName` | `string` | **yes** | Human-readable version (e.g. `1.1.1`). Must match `versionName` in `build.gradle.kts`. |
| `latestVersionCode` | `integer` | **yes** | Monotonically increasing integer. Must match `versionCode` in `build.gradle.kts`. |
| `minSupportedVersionCode` | `integer` | **yes** | Any installed app with `versionCode < minSupportedVersionCode` receives a **required** update (blocking dialog, but never locks out if the check server is down). |
| `downloadUrl` | `string (HTTPS)` | **yes if published** | Direct download URL for the APK. **Must be HTTPS**. The file is verified against the expected package name before install. |
| `releaseNotes` | `string` | **yes if published** | Shown in the update dialog. Plain text, no markdown. |
| `releaseDate` | `string (YYYY-MM-DD)` | **yes if published** | For display and for the "last checked" timestamp logic. |
| `severity` | `enum` | **yes if published** | `OPTIONAL` (non-intrusive) or `REQUIRED` (explains why, provides action, never locks out if server unreachable). |

---

## 3. Release checklist (run **every** release)

> ⚠️ **Do not skip any step.** The update system is a security-sensitive path.

### 3.1 Prepare the APK

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`.
2. Build the **release** APK (or AAB for Play):
   ```bash
   cd android
   ./gradlew.bat :app:assembleRelease
   ```
   Output: `android/app/build/outputs/apk/release/app-release.apk`
3. **Sign** the APK with the production keystore.
   - Keystore is **not** in the repo. It lives in the CI secrets / local machine.
   - Same `applicationId` (`bw.agriconnect`) and compatible signing key as the previous release.
4. **Verify** the signed APK:
   ```bash
   apksigner verify --print-certs app-release.apk
   aapt dump badging app-release.apk | grep package
   ```
   Confirm package name = `bw.agriconnect` and certificate matches previous releases.

### 3.2 Host the APK

- Upload the signed APK to a **trusted HTTPS location** (e.g. GitHub Releases,
  Firebase Hosting, your own CDN). The URL must be stable.
- The download URL **must be HTTPS** — the client rejects HTTP.

### 3.3 Update the manifest

Edit `backend/version-manifest.json`:

```json
{
  "published": true,
  "latestVersionName": "1.1.1",
  "latestVersionCode": 3,
  "minSupportedVersionCode": 2,
  "downloadUrl": "https://github.com/your-org/agriconnect/releases/download/v1.1.1/app-release.apk",
  "releaseNotes": "Fixed satellite boundary editor; added weather history.",
  "releaseDate": "2026-07-20",
  "severity": "OPTIONAL"
}
```

- `published: true` **only** when the APK is live at `downloadUrl`.
- `minSupportedVersionCode`: set to the oldest versionCode that should **not**
  be forced to update. Typically `latestVersionCode - 1` for optional, or the
  version before a breaking API change for required.
- `severity`: `REQUIRED` only for security fixes or breaking API changes.

### 3.4 Deploy the backend (serves the manifest)

```bash
cd backend
git add version-manifest.json
git commit -m "chore(release): v1.1.1 manifest"
git push origin main
```

Render auto-deploys on push to `main` (see `render.yaml` → `startCommand:
npm run migrate && npm start`). The manifest is served at
`GET /api/v1/app/version` immediately after deploy.

### 3.5 Verify the endpoint

```bash
curl https://your-api-host/api/v1/app/version
```

Response should match the manifest exactly (the endpoint normalises but does
not modify the JSON).

### 3.6 (Optional) Google Play In-App Updates

If/when the app is distributed via Google Play, prefer **Play In-App Updates**
(`AppUpdateManager`) over the custom flow. The custom flow remains as a
fallback for direct APK distribution. The manifest format is compatible —
`downloadUrl` would point to the Play Store `market://` intent instead of a
direct APK.

---

## 4. Client-side update logic (Android)

Located in:
- `android/app/src/main/java/bw/agriconnect/data/update/UpdateRepository.kt`
- `android/app/src/main/java/bw/agriconnect/data/update/UpdateChecker.kt`
- `android/app/src/main/java/bw/agriconnect/ui/update/UpdateDialog.kt`

### 4.1 Check flow

```
App start (AgriConnectApp.onCreate)
  -> UpdateScheduler.schedulePeriodic()   (WorkManager, 6h, CONNECTED)
  -> UpdateChecker.checkOnStartup()       (non-blocking, throttled)
            |
    Is check due? (never checked OR last SUCCESSFUL check >= 6h ago) -> NO -> skip
            | YES
    Network available? -> NO -> skip, no timestamp recorded (retry next opportunity)
            | YES
    GET /api/v1/app/version (no auth)
            |
    Success -> record "last checked" timestamp
            |
    decide(currentVersionCode, manifest):
      - installed >= latestVersionCode          -> NONE
      - installed < minSupportedVersionCode     -> REQUIRED update
      - minSupportedVersionCode <= installed < latestVersionCode -> OPTIONAL update
            |
    Show dialog (skip OPTIONAL if the same version code was already deferred)
```

The decision itself is a **pure function** (`decide()` in `UpdateDecision.kt`),
which is what makes the security-relevant rules unit-testable without Android
(`UpdateDecisionTest`). The server never marks a device blocked; the client
alone decides Required vs Optional from its own `versionCode`.

### 4.2 Throttling & deduplication

- **Interval**: `UpdateChecker.CHECK_INTERVAL_MILLIS` = **6 hours**, matching
  `UpdateScheduler.CHECK_INTERVAL_HOURS`. Only a *successful* exchange records a
  timestamp — a failed or offline attempt does not consume the window, so the
  next start or worker tick retries.
- **State**: `SharedPreferencesUpdateStateStore` (file `agriconnect_updates`)
  holds the last-checked timestamp and the deferred version code. It is bound
  behind the `UpdateStateStore` interface so the rules are JVM-tested
  (`UpdateStateRulesTest`).
- **Per-version dismissal**: if the user taps "Update later" on version X, X is
  not offered again until a **newer** version code appears. REQUIRED updates
  ignore dismissal entirely (they can never be deferred).
- **No update -> no interruption**: `available: false`, `installed >= latest`,
  a zero/absent `latestVersionCode`, or a non-HTTPS `downloadUrl` all decide
  `NONE`, and the check completes silently.

### 4.3 Dialog behaviour

| Decision | Dialog type | Dismissible? | Notes |
|----------|-------------|--------------|-------|
| OPTIONAL | Material `AlertDialog` | Yes ("Update later") | "Update later" remembers this versionCode. Back press / outside tap also count as deferring. |
| REQUIRED | Blocking `AlertDialog` | **No** (`dismissOnBackPress = false`, `dismissOnClickOutside = false`; back/outside do nothing) | Only "Update now". **Shown only after a successful check**, so a dead update server can never lock the user out. |

The dialogs are hosted once at the navigation root (`UpdateDialogHost()` in
`AgriConnectRoot`), driven by `UpdateViewModel` / `UpdateChecker` state — never
by `remember` — so they survive recomposition, navigation and configuration
changes.

### 4.4 Download, verify & install

1. **Download** (`ApkDownloader`) with the bare `@ApkDownloadClient` OkHttp
   client — **no** `AuthInterceptor`, so the bearer token is never sent to a
   third-party download host. The file streams to app-private
   `cacheDir/updates/update.apk`.
   - Non-HTTPS `downloadUrl` is rejected before any request.
   - Free-space is pre-checked (`StatFs`) against `sizeBytes` / `Content-Length`.
   - A streaming size guard caps the file (and SHA-256 is computed in the same
     pass). Cancellation deletes the partial file.
2. **Verify** (`ApkVerifier`) — all must pass:
   - SHA-256 matches `checksumSha256` (an absent checksum is not a failure).
   - Archive `packageName` matches the **installed** `applicationId`.
   - Archive `versionCode` is **exactly** the manifest's `latestVersionCode`
     (a stricter rule than "newer than installed": it also rejects a stale or
     swapped file left at the same URL).
   - The signing certificate matches the installed app's signer (including the
     signing history on API 28+, so a legitimate key rotation still verifies).
3. **Install** (`ApkInstaller`):
   - Primary: a `PackageInstaller` session pinned to our package, streamed
     (no world-readable staging file). `STATUS_PENDING_USER_ACTION` launches the
     system's own confirmation intent — Android's install security always gets
     the final say, it is never bypassed. Results arrive via
     `UpdateInstallReceiver` (`exported="false"`).
   - Fallback (OEM quirks): hand the file to the system installer through the
     dedicated FileProvider `${applicationId}.fileprovider.updates`
     (`file_paths_updates.xml`, root `cache/updates/`).
4. **`REQUEST_INSTALL_PACKAGES`** is declared in the manifest but the runtime
   "install unknown apps" grant is requested **only** at the moment the user
   taps "Update now" (Android 8+), after an in-app explanation dialog. It is
   never requested at startup.
5. On success the update is an ordinary in-place upgrade: same `applicationId`
   and signing key, so user data is preserved.
6. On cancellation the partial file is deleted and the prompt returns quietly.
   On failure the user sees actionable copy (storage, blocked, invalid,
   conflict, incompatible, interrupted download).

### 4.5 Failure handling (never lock out)

Every failure path is a promise that a broken update system cannot break the
app:

- A missed, offline, malformed, or 5xx check is **swallowed** (`Log.w` only):
  no dialog, no error, no timestamp. The previous prompt (if any) is untouched.
- The REQUIRED dialog can only ever be raised by a *successful* check.
- Transport errors, verification failures and install failures all surface as
  a dismissible result dialog and then return to the prior state.

### 4.6 Security requirements

- **Never** bypass Android install security — no world-readable files and the
  system installer always confirms.
- **Never** download from HTTP — enforced server-side *and* re-checked in
  `ApkDownloader`/`decide()`.
- **Never** install an APK whose package name or signer does not match the
  installed app, and never one whose version code differs from the published
  release.
- **Never** send the user's bearer token to the download host (bare client).
- **Never** commit signing keys, keystore passwords, or `key.properties` to
  GitHub. Use CI secrets (GitHub Actions `secrets`, Render `envVars`).
- The manifest endpoint is **public** (no auth) so a device can check before
  login — it only returns version metadata, no secrets.

---

## 5. Environment variables (backend)

| Variable | Required | Description |
|----------|----------|-------------|
| `APP_UPDATES_ENABLED` | yes | `true`/`false` — master switch. |
| `APP_VERSION_MANIFEST` | no | Path to manifest file (default: `backend/version-manifest.json`). |
| `APK_DOWNLOAD_URL` | if published | Mirror of `downloadUrl` in manifest (for CI convenience). |
| `APK_PACKAGE_NAME` | yes | `bw.agriconnect` — verified on download. |
| `APP_RELEASE_NOTES_URL` | no | Optional URL to full release notes (shown in dialog). |

---

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| "Update available" but dialog doesn't appear | `severity: OPTIONAL` and user previously dismissed this version | Clear app storage or wait for a newer version. |
| Download fails with "Package name mismatch" | `downloadUrl` points to wrong APK | Verify `aapt dump badging` matches `APK_PACKAGE_NAME`. |
| Install fails with "Parse error" | APK corrupted or not signed | Re-build and re-sign; verify with `apksigner verify`. |
| App crashes on install intent | `FileProvider` authority mismatch | Update installs use `${applicationId}.fileprovider.updates` (see `AndroidManifest.xml` + `file_paths_updates.xml`). |
| Manifest returns `published: false` after deploy | Forgot to set `published: true` in JSON | Edit `version-manifest.json`, commit, push. |

---

## 7. Version history (example)

| Version | Code | Date | Severity | Notes |
|---------|------|------|----------|-------|
| 1.0.0 | 1 | 2026-01-15 | REQUIRED | Initial release |
| 1.0.1 | 2 | 2026-03-10 | OPTIONAL | Bug fixes, avatar upload |
| 1.1.0 | 3 | 2026-06-01 | REQUIRED | Satellite monitoring v1, API v1.1 |
| 1.1.1 | 4 | 2026-07-20 | OPTIONAL | Boundary editor fixes, weather history |

---

## 8. References

- Backend endpoint: `backend/src/modules/app/app.routes.js` → `GET /api/v1/app/version`
- Manifest normalisation: `backend/src/modules/app/app.routes.js` → `normaliseManifest()`
- Android check: `UpdateChecker.kt`, `UpdateRepository.kt`, `UpdateDecision.kt`
- Scheduling: `UpdateScheduler.kt`, `UpdateCheckWorker.kt`
- Dialog UI: `UpdateDialog.kt`, `UpdateViewModel.kt`
- Download: `ApkDownloader.kt` (bare `@ApkDownloadClient`, HTTPS + SHA-256)
- Verification: `ApkVerifier.kt`, `Checksums.kt`
- Install helper: `ApkInstaller.kt` (`PackageInstaller` session, or
  FileProvider `${applicationId}.fileprovider.updates` fallback)
- Install result receiver: `UpdateInstallReceiver.kt`
- Tests: `app/src/test/java/bw/agriconnect/data/update/`
  (`UpdateDecisionTest`, `UpdateVersionApiTest`, `UpdateStateRulesTest`)

---

**Remember:** The manifest is the **source of truth** for the update system.
If it says `published: false`, no update is offered — even if the APK exists
at the URL. This prevents accidental rollouts.