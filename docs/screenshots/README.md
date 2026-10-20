# App Screenshots

Reference screenshots of every screen in the AgriConnect Android app, used for
documentation, store listings, QA review and design sign-off.

> **Status: placeholders.** The folders and the checklist below are set up, but no
> images have been captured yet. There is currently **no device or emulator**
> attached to the build machine, and screens can only be captured on a running
> app. Fill the `*.png` files in by following *How to capture* below, then delete
> this status note.

---

## Folder layout

Each folder holds the screens for one feature area. Suggested filenames are fixed
so screenshots are easy to find and diff between releases:

```
docs/screenshots/
  01-auth/           splash, welcome, onboarding, login, register, forgot-password
  02-dashboard/      dashboard
  03-farms/          farm list, farm detail, harvests, livestock
  04-satellite/      satellite overview, chart, weather, growth, events, alerts,
                     boundary editor
  05-finance/        finance
  06-marketplace/    marketplace, listing detail, create listing
  07-orders/         orders, order detail
  08-notifications/  notifications
  09-profile/        profile
  10-settings/       settings, notification prefs, privacy, offline sync, help,
                     server, account deletion
```

---

## Checklist

Capture **all** rows. Screens marked *(role)* differ by the signed-in role —
capture the role shown. Where a screen has tabs or sheets, capture each one.

### 01-auth

| File | Screen | Notes |
|------|--------|-------|
| `01-auth/splash.png` | Splash | Shown while the session is restored. |
| `01-auth/welcome.png` | Welcome | First launch, before sign-in. |
| `01-auth/onboarding.png` | Onboarding | Swipe through; capture the first page. |
| `01-auth/login.png` | Login | Empty state. |
| `01-auth/register.png` | Register | Empty state. |
| `01-auth/register-validation.png` | Register | With validation errors visible. |
| `01-auth/forgot-password.png` | Forgot password | Empty state. |

### 02-dashboard

| File | Screen | Notes |
|------|--------|-------|
| `02-dashboard/dashboard-farmer.png` | Dashboard (FARMER) | Summary cards, quick actions. |
| `02-dashboard/dashboard-loading.png` | Dashboard | Loading / skeleton state. |
| `02-dashboard/dashboard-empty.png` | Dashboard | Empty state (new account, no plots). |

### 03-farms

| File | Screen | Notes |
|------|--------|-------|
| `03-farms/farm-list.png` | Farm list | With at least one plot. |
| `03-farms/farm-list-empty.png` | Farm list | Empty state. |
| `03-farms/farm-detail.png` | Farm detail | One plot's details. |
| `03-farms/harvests.png` | Harvests | With at least one record. |
| `03-farms/livestock.png` | Livestock | With at least one record. |
| `03-farms/add-plot-boundary.png` | Add/edit plot | Boundary capture (GPS corners or manual lat/lng). |

### 04-satellite

The unified Satellite screen. Capture every tab, plus the boundary editor sheet.

| File | Screen | Notes |
|------|--------|-------|
| `04-satellite/overview.png` | Satellite → Overview | Includes the "newest clear image" freshness banner. |
| `04-satellite/chart-ndvi.png` | Satellite → Chart | NDVI time series. |
| `04-satellite/chart-evi.png` | Satellite → Chart | EVI time series. |
| `04-satellite/weather.png` | Satellite → Weather | Modelled weather; note the source label. |
| `04-satellite/weather-unavailable.png` | Satellite → Weather | `WEATHER_ENABLED=false` or provider down. |
| `04-satellite/growth.png` | Satellite → Growth | Growth-cycle estimate. |
| `04-satellite/events.png` | Satellite → Events | Farmer event log. |
| `04-satellite/event-dialog.png` | Satellite → Events | Confirm/correct dialog. |
| `04-satellite/alerts.png` | Satellite → Alerts | With at least one alert. |
| `04-satellite/alerts-empty.png` | Satellite → Alerts | Empty state. |
| `04-satellite/boundary-rectangle.png` | Boundary editor | Method A — rotated rectangle. |
| `04-satellite/boundary-polygon.png` | Boundary editor | Method B — custom polygon. |
| `04-satellite/no-observations.png` | Satellite → Overview | State with no usable observation yet. |

### 05-finance

| File | Screen | Notes |
|------|--------|-------|
| `05-finance/finance.png` | Finance | Summary and charts. |
| `05-finance/finance-empty.png` | Finance | Empty state. |

### 06-marketplace

| File | Screen | Notes |
|------|--------|-------|
| `06-marketplace/marketplace.png` | Marketplace | Listing grid. |
| `06-marketplace/marketplace-empty.png` | Marketplace | Empty state. |
| `06-marketplace/listing-detail.png` | Listing detail | One listing. |
| `06-marketplace/create-listing.png` | Create listing | Form. |

### 07-orders

| File | Screen | Notes |
|------|--------|-------|
| `07-orders/orders.png` | Orders | List. |
| `07-orders/orders-empty.png` | Orders | Empty state. |
| `07-orders/order-detail.png` | Order detail | One order. |

### 08-notifications

| File | Screen | Notes |
|------|--------|-------|
| `08-notifications/notifications.png` | Notifications | With at least one item. |
| `08-notifications/notifications-empty.png` | Notifications | Empty state. |

### 09-profile

| File | Screen | Notes |
|------|--------|-------|
| `09-profile/profile.png` | Profile | Signed in. |

### 10-settings

| File | Screen | Notes |
|------|--------|-------|
| `10-settings/settings.png` | Settings | Root list. |
| `10-settings/notification-prefs.png` | Notification preferences | |
| `10-settings/privacy.png` | Privacy | |
| `10-settings/offline-sync.png` | Offline / sync | Shows last successful sync time. |
| `10-settings/help.png` | Help | |
| `10-settings/server.png` | Server | Debug builds only — build-time URL plus the override. |
| `10-settings/account-deletion.png` | Account deletion | |

---

## How to capture

### Option A — physical device (recommended)

1. Enable **Developer options → USB debugging** on the phone.
2. Connect it and confirm it is visible:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   ```
   It must show the serial with the state `device` (not `unauthorized` or
   `offline`).
3. Install the debug build and launch it:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r android\app\build\outputs\apk\debug\app-debug.apk
   ```
4. Navigate to a screen, then pull a capture straight off the device:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > docs\screenshots\01-auth\login.png
   ```
   That single command is the whole capture: it writes the PNG directly, with no
   second transfer step and no temporary file on the device.
5. Repeat for every file in the checklist above, pointing the redirect at the
   right path each time.

A small helper saves a lot of typing — run one command per screen and let it name
the file:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Shot($rel) {
  $out = Join-Path "docs\screenshots" $rel
  New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
  & $adb exec-out screencap -p > $out
  Write-Host "saved $out"
}
# then, with the screen you want already on the phone:
Shot "01-auth\login.png"
Shot "02-dashboard\dashboard-farmer.png"
Shot "04-satellite\overview.png"
```

### Option B — emulator

1. Create and start an AVD from Android Studio **Device Manager**, or:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
   & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd <name>
   ```
2. Wait for boot, confirm with `adb devices`, then use the exact same
   `adb exec-out screencap -p > <path>.png` command as above.
3. Bonus: the emulator's **Extended controls → Camera → Take screenshot** also
   saves a PNG, but the `adb` route keeps filenames consistent with Option A.

### Option C — Android Studio

`View → Tool Windows → Logcat` has a screenshot button, or use the running-device
toolbar. Convenient for one-offs; slower for 40+ files.

---

## Rules for a good set

- **Phone height, portrait.** Do not crop; capture the whole screen so the status
  bar and bottom navigation are visible.
- **Real or clearly labelled demo data.** Never present mock or simulated
  satellite readings as live. If a capture shows `isSimulated: true` data, keep
  the on-screen source label visible.
- **No personal data.** Use a test account. Blur or avoid real names, phone
  numbers, and exact addresses.
- **Re-capture after UI changes.** These images are documentation; if a screen
  changes, its screenshot is stale until replaced.
- **Keep the filenames.** Downstream docs, store listings and QA reference the
  paths above, so a renamed file becomes a broken reference.

## Adding screenshots to this repo

Screenshots are ordinary image files and can be committed. Keep them small
(re-capture rather than upscale), and commit them alongside the UI change that
produced them so the history shows *why* an image changed.
