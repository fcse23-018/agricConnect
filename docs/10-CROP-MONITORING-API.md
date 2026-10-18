# 10 — Crop Monitoring API (FastAPI + Google Earth Engine)

> **Status:** service implemented and unit/HTTP-tested in this environment
> (34/34 `pytest`, synthetic mode). The **live** Earth Engine path is written to
> match the validated prototype but has **not** been executed here — it needs a
> registered Earth Engine project and credentials.
>
> **Code:** [`crop-api/`](../crop-api) · **Setup reference:**
> `AgriConnect_Backend_Setup_Documentation.docx`

---

## 1. Where it fits

AgriConnect has **two backend services**, each doing what it is best at:

| Service | Stack | Responsibility |
|---------|-------|----------------|
| `backend/` | Node.js / Express / PostgreSQL | Accounts, farms, harvests, finance, marketplace, orders, notifications, sync, **uploads** |
| `crop-api/` | Python / FastAPI | **Crop monitoring only** — turning a field shape into Sentinel-2 NDVI/EVI via Google Earth Engine |

The three-tier crop-monitoring flow:

```
1. Android app (frontend)   map → select field → tap "Analyse"
        │  POST /v1/health-summary {centre|corners, …}
        ▼
2. FastAPI (backend)        validate → cache → call Earth Engine → clean → summarise
        │  ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        ▼
3. Earth Engine + Sentinel-2   petabytes of imagery, computed on Google's servers
        ▲  a few KB of NDVI/EVI numbers
        └──────────────────────────────────────────────  back to the app
```

Earth Engine and `geeagri` are Python tools and cannot run inside an Android app,
which is exactly why this tier exists.

## 2. The API

See [`crop-api/README.md`](../crop-api/README.md) for the full contract. Summary:

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET` | `/health` | — | Liveness + `gee_enabled` flag |
| `POST` | `/v1/area` | `X-API-Key` | Measured area, min pixels, GeoJSON ring |
| `POST` | `/v1/indices` | `X-API-Key` | Cleaned NDVI/EVI series + skipped readings |
| `POST` | `/v1/health-summary` | `X-API-Key` | Latest reading + plain-language status |

Body (either form; both/neither → `422`):

```jsonc
{ "centre": {"lat": -21.382573, "lon": 27.446793},
  "width_m": 40, "length_m": 70, "bearing_deg": 140, "inset_m": 5, "days_back": 365 }
```

## 3. Two modes: live vs synthetic

| | `GEE_ENABLED=true` (live) | `GEE_ENABLED=false` (default) |
|---|---|---|
| Imagery | Real Sentinel-2 SR Harmonized + Cloud Score+ | Deterministic series seeded from the field's coordinates |
| Needs | Earth Engine project + credentials | Nothing — offline |
| Response `source` | `"gee"` | `"mock"` |

The synthetic mode exists so the service runs, tests and integrates with no
Google account. It is **always labelled** `"source": "mock"` — the app can show
an honest "demo data" badge and never present it as a real observation.

## 4. Cleaning and status rules

Cloud masking (SCL + Cloud Score+), median (not mean), a minimum clear-pixel
count, a haze check (`NDVI − EVI > 0.45`) and an isolated-spike check. The status
is chosen by the first matching rule: `watch` (≤ −0.15) → `bare_or_fallow`
(< 0.35) → `rising` (≥ +0.08) → `declining` (≤ −0.08) → `stable`. NDVI cannot
separate harvest from stress, so the wording says "check the plot" rather than
diagnosing.

## 5. Configuring

Copy `crop-api/.env.example` to `crop-api/.env`:

| Variable | Default | Meaning |
|----------|---------|---------|
| `GEE_ENABLED` | `false` | Use live Sentinel-2 |
| `GEE_PROJECT` | `agriconnect-crop-monitoring` | Earth Engine Cloud project ID |
| `CACHE_TTL_SECONDS` | `21600` | Series cache (imagery changes ~every 5 days) |
| `CROP_API_KEY` | *(empty)* | Shared key required as `X-API-Key`; empty = open (dev only) |
| `CORS_ORIGINS` | `*` | Allowed browser origins |

## 6. Running

```bash
cd crop-api
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000     # /docs
```

or `docker compose up --build`.

## 7. How the Android app reaches it

The service is reached with the **origin** (no `/api/v1/` path) and, when a key
is configured, the `X-API-Key` header. The app should treat it exactly like the
existing satellite module: an unavailable or `"source": "mock"` result is shown
honestly rather than dressed up as a real reading.

A phone on the same LAN uses the PC's IPv4 address (e.g.
`http://192.168.1.20:8000`); the Android emulator uses `http://10.0.2.2:8000`.

## 8. Limitations (unchanged from the prototype doc)

- **Greenness only.** NDVI cannot separate harvest from stress; weather
  (CHIRPS rainfall, ERA5-Land temperature/soil moisture) is the suggested next
  step.
- **In-memory cache**, process-local; resets on restart. Swap for Redis when
  scaled out.
- **One validated test plot.** Thresholds are tuned to irrigated vegetables.
- **Stateless.** The service stores no fields — the app owns the boundary.
