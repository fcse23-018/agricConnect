# AgriConnect — Requirements Summary, Conflicts and Resolutions

> **Status:** Working document. This is Deliverable / Step 1 of the build brief.
> **Sources read (all four attachments):**
> 1. `AgriConnect_Architecture_UML_Suite.docx` (v1.0)
> 2. `AgriConnect_Architecture_UML_Suite_v1.1.docx` (v1.1 — later revision, treated as authoritative where the two disagree)
> 3. `AgriConnect_Coding_Implementation_Plan (draft).docx`
> 4. `Open Requirements requiring confirmation.docx`
> Plus the master development prompt, `logo.png` and `app layout.png`.

---

## 1. What AgriConnect is

An **offline-first** farm-management ecosystem for smallholder farmers and produce buyers in
Botswana. Farmers record harvests, livestock, sales and expenses on an Android device that works
fully offline; records are queued locally and synchronised to a cloud backend when connectivity
returns. The platform also maps farm plots, retrieves satellite crop-health metrics (NDVI) from
Google Earth Engine, caches them on-device, and provides a produce marketplace with a buyer→order→
farmer-confirmation workflow.

### 1.1 Documented design principles (authoritative)

| # | Principle | Source |
|---|-----------|--------|
| P1 | **Offline first** — the local Room/SQLite database is the single source of truth on the device; every write succeeds locally and syncs later. | Architecture v1.1 §1 |
| P2 | **Decoupled sync engine** — WorkManager + ConnectivityMonitor own queueing, network detection and retries. The UI never waits on the network. | v1.1 §1 |
| P3 | **Deterministic conflict resolution** — LastWriteWinsResolver using client/server timestamps. | v1.1 §1 |
| P4 | **Cached intelligence** — NDVI is computed in the cloud; a compressed vector alert is cached locally for offline viewing. | v1.1 §1 |
| P5 | **Role-based access** — three roles (FARM_OWNER, FARMER, BUYER) enforced by the backend. All three may buy. **No facial recognition / biometrics.** | v1.1 §9 |

---

## 2. Documented technology stack (must be preserved)

| Layer | Technology | Source |
|-------|-----------|--------|
| Mobile UI | Kotlin, Jetpack Compose + Material 3 (v1.1 says "Compose & ViewBinding") | v1.1 §3 |
| Local storage | Room ORM over SQLite | v1.1 §3 |
| Background sync | Android WorkManager + ConnectivityManager | v1.1 §3 |
| API client | Retrofit 2 / OkHttp 4, JWT bearer, HTTPS | v1.1 §3 |
| Cloud service | Node.js + Express, RBAC, validation, conflict resolution | v1.1 §3 |
| Central DB | PostgreSQL 15 | v1.1 §3 |
| Satellite | Google Earth Engine / Sentinel-2 | v1.1 §3 |
| Mapping | Android-compatible map supporting farm boundaries + GeoJSON (brief §2) | brief |

---

## 3. Documented domain model (v1.1 class diagram — authoritative)

Entities: `User`, `FarmPlot`, `SatelliteNDVI`, `HarvestRecord`, `LivestockCount`, `Expense`,
`SaleTransaction`, `ProduceListing`, `Order`, `Notification`, `SyncQueueItem`.

Enums:

```
UserRole      = FARM_OWNER, FARMER, BUYER
SyncStatus    = PENDING, SYNCED, CONFLICT, FAILED
ListingStatus = DRAFT, PENDING_PUBLISH, SYNCING, RETRY, PUBLISHED, SOLD
OrderStatus   = PENDING, CONFIRMED, COMPLETED, CANCELLED
```

Key attributes carried forward verbatim:

- `FarmPlot`: plotId, farmerId, plotName, areaHectares, geoJsonBoundary, createdAt, syncStatus
- `SatelliteNDVI`: ndviId, plotId, satelliteDate, meanNdviValue, compressedVectorJson, isCached, syncStatus
- `HarvestRecord`: harvestId, farmerId, cropType, quantityKg, harvestDate, syncStatus
- `LivestockCount`: countId, farmerId, animalType, headCount, recordDate, syncStatus
- `Expense`: expenseId, farmerId, category, amount, expenseDate, syncStatus
- `SaleTransaction`: transactionId, farmerId, item, quantity, amount, date, syncStatus
- `ProduceListing`: listingId, farmerId, produce, quantity, price, status, syncStatus
- `Order`: orderId, listingId, buyerId, quantity, totalPrice, status, syncStatus
- `Notification`: notificationId, … (source truncated at that point — fields inferred, see §5)
- `SyncQueueItem`: queueId, entityType, entityId, operation, payload, createdAt, status, retryCount

---

## 4. Documented numeric thresholds (v1.1 §9)

| Rule | Value |
|------|-------|
| Local write latency target | < 50 ms |
| WorkManager periodic sync interval | 15 minutes |
| Backoff schedule | 10s → 20s → 40s → 80s … up to 1 hour |
| NDVI High Stress | `NDVI < 0.20` |
| NDVI Moderate Stress | `0.20 ≤ NDVI < 0.45` |
| NDVI Healthy Vegetation | `NDVI ≥ 0.45` |
| NDVI processing failure | cloud cover > 80 % ⇒ `FAILED` |
| NDVI bands | Sentinel-2 Band 8 (NIR), Band 4 (RED) |
| NDVI formula | `(NIR − RED) / (NIR + RED)` |

---

## 5. Requirement gaps in the source documents that the build must fill

These are *not* conflicts — they are fields/workflows the diagrams leave blank. The brief requires
them, so they are implemented and documented as **inferred**, not invented silently:

| # | Gap | Resolution adopted |
|---|-----|--------------------|
| G1 | `User` has no email, status, createdAt, updatedAt, verification fields | Added: `email` (optional), `accountStatus` (REGISTERED/UNVERIFIED/VERIFIED/SUSPENDED/PENDING_DELETION/DELETED), timestamps. |
| G2 | `Notification` fields truncated in source | Implemented as: notificationId, userId, type, title, body, relatedEntityType, relatedEntityId, isRead, createdAt, syncStatus. |
| G3 | No `SyncQueueItem` ownership column although class diagram associates it to a User | Added `userId` to scope queued work per account (required for safe logout/deletion). |
| G4 | No money type specified | Amounts stored as `NUMERIC(14,2)`, currency fixed to **BWP (Botswana pula)** per brief §11. |
| G5 | No explicit auth identity | **Decided with the product owner:** phone number is the primary required identifier; **email is optional**. Login accepts either. |
| G6 | No password-recovery delivery channel | Delivery abstraction implemented; **no provider configured**. Dev-only outbox; documented as not production-ready. |
| G7 | `Order` has no timestamps/notes | Added createdAt, updatedAt, farmerDecisionAt, note. |

---

## 6. Conflicts found, and how each is handled

| # | Conflict | Sources | Resolution |
|---|----------|---------|------------|
| C1 | **Auth identifier** — docs say phone number; the brief allows "phone number *or* email, according to the selected authentication design". | Architecture vs brief §4B | **Resolved with user:** phone required, email optional, either can log in. |
| C2 | **PIR motion camera** — the draft implementation plan assigns a `MotionLogger.kt` "PIR sensor input" task, and the "next steps" doc measures "PIR motion camera setup"; but v1.1 states explicitly *"No camera or motion-sensor component appears in any diagram"* and biometrics are removed. | Plan & Next-Steps vs v1.1 | **PIR excluded.** v1.1 is the later, authoritative architecture. Recorded as an outstanding conflict for the owners. |
| C3 | **Listing state names** — v1.0 `ListingStatus{DRAFT,PENDING_PUBLISH,PUBLISHED,SOLD}` vs v1.1 `{DRAFT,PENDING_PUBLISH,SYNCING,RETRY,PUBLISHED,SOLD}`. | v1.0 vs v1.1 | **v1.1 enum used** (six states). |
| C4 | **Who confirms an order** — v1.1 permission matrix gives *both* Farmer and Buyer "Manage Orders & Confirm Deliveries", while the narrative says the *farmer* confirms. | v1.1 §9 narrative vs §9.1 matrix | Farmer confirmation is the primary workflow. Buyer may only **cancel** their own PENDING order. Documented. |
| C5 | **Email/SMS provider not configured** vs brief's "working password-recovery process". | Brief §4D | Full token lifecycle implemented; delivery is a pluggable provider with a **dev-only outbox**. Clearly labelled non-production. |
| C6 | **iOS** — brief §16 requires iOS only *if actually developed*; docs specify a native Kotlin **APK** only. | Brief vs docs | **No iOS target.** A native Kotlin app cannot be submitted to the App Store. Recorded. |
| C7 | **SRS not provided** — the brief and traceability matrices reference FR-01…FR-42 / NFR-01…NFR-05, but no SRS file was attached. | Traceability matrices | Traceability matrix maps to the FR/NFR IDs *as cited in the diagrams* and flags that the SRS numbering must be confirmed. |
| C8 | **ViewBinding vs Compose** — v1.1 says "Jetpack Compose & ViewBinding"; the brief mandates Compose + Material 3. | v1.1 §3 vs brief §2 | **Compose + Material 3** only (ViewBinding not used). |
| C9 | **Payments** — brief §13 excludes formal online payment; marketplace needs an order total. | brief §13 | Orders carry a computed `totalPrice` but **no payment flow**. Documented. |
| C10 | **Map SDK** — brief requires "an appropriate Android-compatible map solution"; docs name no SDK. | brief §2 | **OSMDroid (OpenStreetMap)** chosen: free, no API key, offline tile support — appropriate for a Botswana offline-first pilot. Swappable behind a `MapProvider` interface. Requires product-owner sign-off. |

---

## 7. Role permission matrix (authoritative, from v1.1 §9.1 + brief §8)

| Capability | Farmer | Farm Employee | Buyer |
|------------|:------:|:-------------:|:-----:|
| Log crop harvest & livestock | ✅ | ✅ | ❌ |
| Record sales & farm expenses | ✅ | ❌ | ❌ |
| View financial summaries | ✅ | ❌ | ❌ |
| Map farm plot boundary (GeoJSON) | ✅ | ❌ | ❌ |
| View satellite NDVI crop-health alert | ✅ | ❌ | ❌ |
| Post marketplace produce listing | ✅ | ❌ | ❌ |
| Browse produce & place order | ✅ (own) | ❌ | ✅ |
| Manage orders / confirm deliveries | ✅ | ❌ | ✅ (cancel own) |
| Manage profile & account | ✅ | ✅ | ✅ |
| Receive notifications | ✅ | ✅ | ✅ |
| Synchronise offline queue | auto | auto | auto |

Enforced **three times**: UI navigation guards, API middleware, and database ownership queries.

---

## 8. Unresolved requirements still requiring product-owner confirmation

| # | Item | Needed from owner |
|---|------|-------------------|
| U1 | Real SMS/email delivery provider + credentials for password recovery | Twilio/SMTP details, sender IDs |
| U2 | Google Earth Engine service-account / project ID | GEE credentials, project, cost plan |
| U3 | Map provider sign-off (OSM chosen by default) | confirm or supply key-based provider |
| U4 | Legal review of Privacy Policy / ToS wording and retention periods | legal/regulatory input (Botswana Data Protection Act) |
| U5 | Definitive data-retention periods for financial/transaction records | finance/legal |
| U6 | SRS numbering (FR/NFR) to finalise the traceability matrix | SRS document |
| U7 | Whether email verification is required when an email is supplied | product |
| U8 | Setswana localisation (documented as a later Phase-3 item) | confirm timing |
| U9 | Store signing keystore ownership | release manager |
| U10 | Production hosting + TLS certificate for the API | DevOps |

---

## 9. Traceability (summary — full matrix in `04-TRACEABILITY-MATRIX.md`)

Each diagram maps to FR/NFR IDs which map to code modules, tests and this doc set. The full matrix
links: requirement → diagram → API endpoint → module → test → status.

---

## 10. What "done" means for this build pass

Deliverable list from brief §18 (1–19) is tracked in `README.md` and
`08-KNOWN-LIMITATIONS-AND-UNVERIFIED.md`. Because the build machine initially had **no installed
toolchains**, every build/test claim is recorded honestly in `05-TEST-REPORT.md` — nothing is
reported as passing unless it was actually executed.
