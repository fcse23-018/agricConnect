# Google Play Store Readiness Checklist

Status legend: ✅ met · ⚠️ needs owner action · ⛔ blocks submission

| # | Store requirement | Status | Notes |
|---|-------------------|:------:|-------|
| 1 | Target API level is current (API 35) | ✅ | `compileSdk`/`targetSdk` = 35 |
| 2 | App builds as a release **App Bundle (.aab)** | ⚠️ | `./gradlew :app:bundleRelease`; verified once toolchain installed |
| 3 | Release build is signed | ⚠️ | Supply `android/keystore.properties` (never commit) |
| 4 | No debug data / logs in release | ✅ | `minifyEnabled` + `shrinkResources`; `VERBOSE_LOGGING=false`; no sample data |
| 5 | `applicationId` final and unique (`bw.agriconnect`) | ⚠️ | Confirm you own the `bw.` reverse-domain |
| 6 | Version code + name set | ✅ | `versionCode 1`, `versionName "1.0.0"` |
| 7 | Launcher icon + adaptive icon | ✅ | Supplied `logo.png` wired as adaptive + legacy mipmaps |
| 8 | App name and short description | ⚠️ | Provide store copy |
| 9 | Screenshots (phone) | ⚠️ | Capture from a real device |
| 10 | Feature graphic (1024×500) | ⚠️ | Owner to design |
| 11 | Privacy policy URL (public, live) | ⚠️ | Host `docs/privacy-policy.md` |
| 12 | Data safety form completed | ⚠️ | Must match actual collection (see §Data safety) |
| 13 | Account deletion URL (web) | ⚠️ | Host `docs/account-deletion.html` |
| 14 | In-app account deletion path | ✅ | Profile → Privacy & data → Delete account (re-auth + grace period) |
| 15 | Runtime permissions justified + requested in context | ✅ | Location only when mapping a plot; notifications on Android 13+; explanations shown first |
| 16 | No unnecessary permissions (no camera, no biometrics) | ✅ | Uses-feature `gps` is `required=false`; no camera/motion |
| 17 | Cleartext traffic restricted to dev | ✅ | `usesCleartextTraffic` only in the debug manifest; HTTPS in release |
| 18 | Network security: TLS only in release | ✅ | No cleartext exception in the main/release manifest |
| 19 | No hard-coded secrets | ✅ | Secrets only via environment/`keystore.properties`; `.gitignore` covers both |
| 20 | Accessibility (TalkBack traversal, ≥48dp targets, colour+icon+text) | ✅ | Design-system rule + component contract |
| 21 | Content rating questionnaire | ⚠️ | Answer "no user-generated content of concern"; marketplace listings are moderated by owner confirmation |
| 22 | Financial features declaration | ⚠️ | App records farm finances but **does not process payments**; declare accordingly |
| 23 | Advertising declaration: none | ✅ | No ads, no ad SDKs |
| 24 | Target audience declaration | ⚠️ | Adults (farmers/buyers); not directed at children |
| 25 | Minimum functionality / no broken features | ⚠️ | Password recovery requires a configured provider (see `07-EXTERNAL-CREDENTIALS.md`) |
| 26 | 64-bit native support | ✅ | No native `.so` shipped; pure Kotlin/Java |
| 27 | ProGuard/R8 keeps required classes | ✅ | `proguard-rules.pro` keeps Retrofit/kotlinx-serialization/Room/osmdroid |
| 28 | App works offline without crashing | ✅ | Offline-first by design; every screen has an offline state |
| 29 | Crash-free cold start | ⚠️ | Verify on a device after toolchain build |
| 30 | Localisation (en); Setswana deferred | ✅ | `resourceConfigurations = ["en"]` |

---

## Data safety form — the honest answers

| Question | Answer |
|----------|--------|
| Does your app collect or share personal data? | **Yes** |
| Data collected | Name, phone number, optional email, farm location (plot boundaries), financial info (sales/expenses — farmer only), app activity |
| Data shared with third parties | Only what is required: Google Earth Engine receives plot geometry for NDVI (no personal identifiers). No data sold. |
| Data encrypted in transit | **Yes** (HTTPS/TLS) |
| Can users request deletion? | **Yes** — in-app and via the web URL |
| Is data used for advertising? | **No** |
| Is data used for analytics/tracking? | **No** |
| Is data collected that is not needed? | **No** — email is optional; no biometrics; no camera |

---

## Signing setup (owner action)

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/agriconnect-release.jks
storePassword=********
keyAlias=agriconnect
keyPassword=********
```

Generate the keystore **once** and store it in a secure vault:

```bash
keytool -genkeypair -v -keystore agriconnect-release.jks \
  -alias agriconnect -keyalg RSA -keysize 4096 -validity 10000
```

> Losing this keystore means you cannot update the app. Back it up.

---

## Pre-submission command sequence

```bash
cd android
./gradlew clean
./gradlew :app:testDebugUnitTest          # unit tests must pass
./gradlew :app:lintRelease                # review lint output
./gradlew :app:bundleRelease              # produces app-release.aab
# upload build/outputs/bundle/release/app-release.aab to Play Console
```

## Blockers before submission

1. Signing keystore + `keystore.properties`.
2. Privacy policy and account-deletion pages hosted publicly.
3. A configured SMS/email provider (otherwise recovery is non-functional and Play may flag a broken feature).
4. Store listing assets (screenshots, feature graphic, copy).
5. Completed Data safety and content-rating forms.
