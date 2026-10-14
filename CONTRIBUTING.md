# Contributing to AgriConnect

Thanks for your interest in AgriConnect — an offline-first farm management, satellite
crop-insight and produce-marketplace platform for smallholder farmers in Botswana.

This document explains how to get set up, what we expect from a change, and how to
submit it.

---

## Code of conduct

Be respectful, assume good faith, and keep discussion focused on the work. Harassment
or discriminatory behaviour is not tolerated in issues, pull requests or any project
channel.

---

## Repository layout

```
agriConnect/
├─ android/    Native Android app (Kotlin · Jetpack Compose · Room · Hilt)
├─ backend/    Node.js/Express REST service + PostgreSQL migrations
└─ docs/       Written deliverables (requirements, design, API contract, …)
```

See [`README.md`](README.md) for the full deliverable index.

---

## Getting started

### Backend

```bash
cd backend
cp .env.example .env          # then fill in secrets / database values
npm install
npm run migrate               # apply migrations/*.sql in order
npm run seed                  # optional development data
npm start                     # http://localhost:4000  (docs at /api-docs)
npm test                      # unit + API tests
```

Requires **Node.js ≥ 18.18** and **PostgreSQL 16**.

### Android

Requires **JDK 17** and the **Android SDK (compileSdk 35)**. From `android/`:

```bash
./gradlew :app:assembleDebug            # debug APK (points at http://10.0.2.2:4000)
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:connectedDebugAndroidTest # instrumented tests (device/emulator)
```

> The debug build talks to `http://10.0.2.2:4000/api/v1/` (the host machine as seen
> from an Android emulator).

---

## Making a change

1. Create a topic branch: `git checkout -b feat/short-description`.
2. Keep the change focused — one logical change per pull request.
3. Follow the conventions below.
4. Run the relevant tests locally (see above) and make sure they pass.
5. Push your branch and open a pull request against `main`.

### Commit messages

Use short, imperative subject lines, optionally with a scope:

```
auth: rotate refresh tokens on password reset
marketplace: reject publishing a listing with no stock
android: add offline map tile cache
```

### Pull requests

A good pull request:

- explains **why** the change is needed, not just what it does;
- lists the tests you ran;
- updates docs when behaviour, the API contract, or configuration changes;
- does **not** include secrets, generated build output, or unrelated reformatting.

---

## Conventions

### Backend (JavaScript / ESM)

- The backend is native ESM (`"type": "module"`) — use `import`/`export`.
- Validate every request body/query with the project's Zod schemas via the
  `validate(...)` middleware; never trust client input.
- Route access is enforced with role middleware (`farmerOnly`, `farmerOrEmployee`,
  `buyerOrFarmer`, `authorizeRoles`) — the UI hiding a control is never the
  enforcement point.
- Never leak internals: use the shared error helpers so responses follow the
  documented error envelope.
- Money is `NUMERIC(14,2)` in BWP and is serialised through `utils/serialize.js`.
- Update [`backend/openapi.yaml`](backend/openapi.yaml) and
  [`docs/02-API-CONTRACT.md`](docs/02-API-CONTRACT.md) whenever you add or change an
  endpoint.

### Android (Kotlin)

- Kotlin official code style; Jetpack Compose with Material 3.
- Keep the domain layer free of Android/framework imports.
- Model state as immutable data; surface errors through the shared result type.
- New screens belong under `ui/`, reusing the components in `ui/components`.

### Documentation

- Deliverables live in `docs/` and are referenced from the README index. Keep the two
  in sync; if you add a document, add it to the index.

---

## Security

- **Never commit secrets.** `.env`, keystores (`*.jks`, `keystore.properties`), service
  accounts and `local.properties` are ignored — keep it that way.
- Report a suspected vulnerability privately to the maintainers rather than opening a
  public issue.

---

## Licence

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE) that covers this project.
