# Data Retention, Account Deletion and Privacy Engineering

This document states exactly what AgriConnect stores, why, for how long, and what happens when a
user deletes their account. It is written to be defensible under the **Botswana Data Protection
Act, 2024** and to satisfy the app-store requirement that a user can request deletion from outside
the app.

> **Not legal advice.** The retention periods below are engineering defaults. Legal counsel must
> confirm them before production (tracked as U4/U5 in `00-REQUIREMENTS-SUMMARY-AND-CONFLICTS.md`).

---

## 1. What we store

| Category | Data | Where | Sensitivity |
|----------|------|-------|-------------|
| Identity | full name, phone number, optional email, password hash | `users` | Personal |
| Credentials | refresh tokens, verification/reset codes (all **hashed**) | `refresh_tokens`, `verification_tokens` | Secret |
| Farm operations | plots + GeoJSON boundaries, harvests, livestock counts | `farm_plots`, `harvest_records`, `livestock_counts` | Personal + business |
| Financial | sales, expenses | `sale_transactions`, `expenses` | Business / financial |
| Marketplace | listings, orders | `produce_listings`, `orders` | Business |
| Satellite | NDVI values, observation dates, vector alerts | `satellite_ndvi` | Business |
| Preferences | notification preferences, avatar | `users` | Personal |
| Notifications | in-app notification feed | `notifications` | Personal |
| Security | audit log of security events | `audit_log` | Operational |
| Delivery | outbound message log (development only) | `message_outbox` | Secret-adjacent |

We **never** store: plaintext passwords, un-hashed tokens, biometric data (explicitly excluded by
the architecture) or camera/motion data (the PIR component was excluded — see conflict C2).

---

## 2. Retention schedule

| Data category | Retention | Justification |
|---------------|-----------|---------------|
| Identity (name, phone, email, avatar, preferences) | Until deletion **+ grace period**, then anonymised | Minimum necessary to operate the account |
| Password hash | Until password change or deletion | Authentication only |
| Refresh tokens / verification codes | 30 days (refresh) / 15 min (reset) / 10 min (verify), or on use | Security; rotated and single-use |
| Plots, harvests, livestock | Until deletion, then **deleted** | Private farm operations; no legal need to keep |
| Satellite NDVI history | Until deletion, then **deleted** | Derived from plots |
| Sales & expenses | Until deletion, then **de-identified**, retained **84 months (7 years)** | Financial/tax record-keeping |
| Orders & listings | Until deletion, then **de-identified**, retained **84 months** | Commercial-dispute and tax record-keeping |
| Audit log | 24 months rolling | Security incident investigation |
| Notification feed | 12 months rolling, then purged | Not operationally needed beyond that |
| Message outbox | Development only; purge before production | Contains codes — must never reach production |
| Backups | 30 days rolling | Disaster recovery; deletion propagates on next rotation |

**De-identification** means the retained row keeps its `farmer_id`/`buyer_id` foreign key pointing at
an account row that no longer contains any personal data (the identifiers are replaced — see §4).
A non-reversible `pseudonym` is recorded in `retention_ledger` so retained records can still be
distinguished without re-identifying anyone.

---

## 3. Account deletion — what actually happens

The brief forbids a UI-level "delete" that leaves the account alive server-side. The real flow:

```
User taps Delete account
   │
   ├─ Re-authenticate: password required again (never trust the session alone)
   │
   ├─ POST /users/me/deletion-request  { password, reason? }
   │     • accountStatus -> PENDING_DELETION
   │     • ALL refresh tokens revoked  (every device signed out)
   │     • account_deletion_requests row: status=PENDING, effective_at = now + 14 days
   │     • retention_ledger rows written (what will be kept and why)
   │     • audit_log: ACCOUNT_DELETION_REQUESTED
   │
   ├─ 14-day grace period   (GET /users/me/deletion-request shows the effective date;
   │                         POST .../cancel restores the account)
   │
   └─ Effective date reached
         • personal fields anonymised (name -> "Deleted user", phone/email -> NULL,
           password hash -> unverifiable, avatar -> NULL, preferences -> {})
         • device tokens, refresh tokens, unconsumed verification codes deleted
         • plots / harvests / livestock / NDVI DELETED (hard)
         • sales / expenses / orders / listings KEPT, now de-identified
         • accountStatus -> DELETED, deleted_at set
         • deletion request -> COMPLETED
         • audit_log: ACCOUNT_ANONYMISED
```

### Why a grace period?

It protects a user who deletes by mistake or under duress, and it satisfies a common store/regulatory
expectation that deletion is deliberate. The user is told the exact date, and can cancel. A user who
has synced offline data is warned that pending changes will be uploaded **and then deleted** so
nothing is silently lost — the app flushes the sync queue before honouring the request.

### Message shown before confirming (verbatim)

> Deleting your account signs you out everywhere and removes your farm records and satellite
> history permanently. Sales, expenses and order records are kept without your name for seven years
> because we are required to keep financial records. This cannot be undone after the grace period.
> **Your changes that are still waiting to sync will be uploaded first, then deleted.**

---

## 4. Anonymisation detail (implemented in `backend/src/modules/account/anonymize.js`)

| Column | Before | After |
|--------|--------|-------|
| `full_name` | "Thabo Mokoena" | `"Deleted user"` |
| `phone_number` | `+26771234567` | `NULL` (unique index allows many NULLs) |
| `email` | `thabo@example.bw` | `NULL` |
| `password_hash` | bcrypt hash | random non-verifiable string |
| `avatar_url` | URL | `NULL` |
| `notification_preferences` | object | `{}` |
| `account_status` | `VERIFIED` | `DELETED` |
| `deleted_at` | `NULL` | timestamp |

Releasing `phone_number`/`email` means the person can register again later; the old row remains only
as a de-identified anchor for retained financial records.

---

## 5. External deletion request (app-store requirement)

Google Play requires a web-accessible route to request deletion **without installing the app**:

- Page: [`docs/account-deletion.html`](account-deletion.html) (host at e.g.
  `https://agriconnect.bw/account-deletion`).
- Endpoint: `POST /api/v1/public/account-deletion` — always returns a generic `202` so it never
  reveals whether an account exists.
- A request made externally enters the **same** PENDING flow, grace period and retention ledger.

---

## 6. Privacy engineering controls

| Control | Implementation |
|---------|----------------|
| Passwords | bcrypt (cost 12) / never logged |
| Tokens | stored hashed (`sha256`); single-use; short TTL; rotated on refresh; reuse detection revokes all |
| Transport | HTTPS only; HSTS in production |
| At-rest (device) | tokens in `EncryptedSharedPreferences`; app DB in private storage; Android backup/exfil **disabled** |
| Least privilege | three roles enforced server-side; the farmer who works the land never sees financials |
| Data minimisation | email optional; no biometrics; no camera |
| Logging | request id correlation; no PII/token/password in logs |
| Crash reporting | disabled by default (no PII leaves the device) |
| Consent | ToS + Privacy acceptance recorded at registration |

---

## 7. Operational tasks (must exist before production)

1. **Scheduled anonymisation job** — run `processDueDeletions()` daily (cron/queue worker).
2. **Notification purge** — delete notifications older than 12 months.
3. **Audit purge** — delete audit rows older than 24 months.
4. **Outbox purge** — the development `message_outbox` must be disabled/purged in production.
5. **Backup rotation** — 30 days; verify deletions propagate.
6. **Data export** — `GET /users/me/export` already returns a machine-readable copy on request.
