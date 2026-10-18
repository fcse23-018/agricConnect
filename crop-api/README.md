# AgriConnect Crop Monitoring API (FastAPI)

The "messenger" tier of the crop-monitoring module. It sits between the
AgriConnect Android app and Google Earth Engine:

```
 Android app            FastAPI (this service)          Google Earth Engine
 ┌───────────┐          ┌──────────────────────┐        ┌───────────────────┐
 │ map +     │  POST    │  validate the shape  │  EE    │ Sentinel-2 imagery│
 │ field tap │ ───────► │  cache + clean data  │ ─────► │ Cloud Score+, SCL │
 │ "Analyse" │  JSON    │  summarise status    │  API   │ NDVI / EVI median │
 └───────────┘ ◄─────── └──────────────────────┘ ◄───── └───────────────────┘
                a few KB            the heavy work stays on Google's servers
```

FastAPI owns **no** visual design and **no** field data: each request carries the
shape to measure, and only a few kilobytes of numbers come back. That is why
this runs as a separate service rather than inside the Android app.

> The existing Node/Express backend still serves the rest of the product (auth,
> farms, marketplace, orders, uploads…). This service is only the Earth Engine
> worker. See [`../docs/10-CROP-MONITORING-API.md`](../docs/10-CROP-MONITORING-API.md).

---

## Quick start

```bash
cd crop-api
python -m venv .venv
.venv\Scripts\activate            # Windows;  source .venv/bin/activate elsewhere
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open <http://localhost:8000/docs> for the auto-generated test page.

By default `GEE_ENABLED=false`, so the service answers with a **deterministic
synthetic series** and tags every response `"source": "mock"`. Nothing is ever
presented as a real satellite reading. Flip on live data by copying
`.env.example` to `.env`, setting `GEE_ENABLED=true`, installing
`earthengine-api`, and logging in:

```bash
pip install earthengine-api
python -c "import ee; ee.Authenticate()"
python -c "import ee; ee.Initialize(project='agriconnect-crop-monitoring'); print(ee.Number(1).add(1).getInfo())"
```

---

## Endpoints

| Method | Path                 | Key | Purpose |
|--------|----------------------|-----|---------|
| `GET`  | `/health`            | no  | Liveness; reports the live/mock mode. |
| `POST` | `/v1/area`           | yes | Measured area + minimum pixels + GeoJSON ring (no satellite call). |
| `POST` | `/v1/indices`        | yes | Full NDVI/EVI series, cleaned, with the skipped readings and why. |
| `POST` | `/v1/health-summary` | yes | Latest reading, change since ~2 weeks earlier, plain-language status. |

All three `POST` routes take the same body. Give the area **either** as corners
**or** as a centre plus size — sending both or neither is a `422`.

```jsonc
// Rectangle form
{
  "centre": { "lat": -21.382573, "lon": 27.446793 },
  "width_m": 40, "length_m": 70, "bearing_deg": 140,
  "inset_m": 5, "days_back": 365
}
```

```jsonc
// Corners form
{
  "corners": [
    { "lat": -21.38250, "lon": 27.44660 },
    { "lat": -21.38250, "lon": 27.44700 },
    { "lat": -21.38285, "lon": 27.44700 },
    { "lat": -21.38285, "lon": 27.44660 }
  ],
  "inset_m": 5
}
```

`/v1/health-summary` example response:

```json
{
  "area_m2": 1800.0,
  "summary": {
    "state": "rising",
    "message": "Cover is increasing: crop establishing or growing.",
    "latest": { "date": "2026-10-15", "ndvi": 0.6623, "evi": 0.3891, "pixels": 32 },
    "compared_with": { "date": "2026-09-27", "ndvi": 0.4777, "evi": 0.27, "pixels": 32 },
    "ndvi_change": 0.1846,
    "days_between": 18,
    "data_age_days": 2
  },
  "readings_used": 45,
  "source": "gee",
  "generated_at": "2026-10-17T09:12:03+00:00"
}
```

### Parameters

| Field | Allowed | Meaning |
|-------|---------|---------|
| `centre` | `{lat, lon}` | Used with `width_m` + `length_m`. |
| `width_m`, `length_m` | > 5, ≤ 2000 | Rectangle size in metres. |
| `bearing_deg` | 0 ≤ x < 360 | Direction of the **long** side, clockwise from north. |
| `corners` | 3+ points | Alternative to centre + size. |
| `inset_m` | 0–50 (default 5) | Shrink inwards to drop mixed edge pixels. |
| `days_back` | 15–730 (default 365) | How far back to read. |

---

## Status rules

The latest reading is compared with the newest reading **at least 10 days
older**; the first matching rule wins.

| Rule | State | Message |
|------|-------|---------|
| NDVI change ≤ −0.15 | `watch` | Sharp drop — could be harvest/clearing, **or** stress. Check the plot. |
| NDVI < 0.35 | `bare_or_fallow` | Very little green cover. |
| NDVI change ≥ +0.08 | `rising` | Crop establishing or growing. |
| NDVI change ≤ −0.08 | `declining` | Maturing/ageing crop, or early stress. |
| otherwise | `stable` | Greenness is steady. |
| no readings | `no_data` | No clear satellite readings in this period. |

NDVI alone cannot tell a harvest from stress, so the wording never claims a
diagnosis.

## Data cleaning (both modes)

* **Cloud masking** — Sentinel-2 scene classification (keep vegetation + bare
  soil) combined with Cloud Score+ (`cs_cdf ≥ 0.6`).
* **Median, not mean** — one stray bright pixel cannot drag the result.
* **Pixel check** — readings with fewer than half the area's expected pixels
  (minimum 3) are dropped.
* **Haze check** — drop when `NDVI − EVI > 0.45`.
* **Spike check** — drop a reading that jumps > 0.3 from both neighbours in the
  same direction.

---

## Access control

Set `CROP_API_KEY` to a long random value; every `/v1/*` call then needs it:

```bash
curl -s http://localhost:8000/v1/health-summary \
  -H "X-API-Key: $CROP_API_KEY" -H "Content-Type: application/json" \
  -d '{"centre":{"lat":-21.382573,"lon":27.446793},"width_m":40,"length_m":70,"bearing_deg":140}'
```

Without a key the service still runs for local development but logs a warning at
startup. `/health` is always open so a load balancer can probe it.

---

## Tests

```bash
.venv\Scripts\python.exe -m pytest -q
```

The suite is hermetic — no Earth Engine, no network, no API key. It covers the
geometry (including the 40 × 70 m → ~1800 m² upper-bound check), the cleaning
rules, every status branch, the request contract, and the whole HTTP surface in
synthetic mode.

## Stand-alone plot tool

`scripts/ndvi_plot.py` marks an area, writes a Leaflet preview and exports a
CSV, reusing the same geometry/EE code as the service:

```bash
python scripts/ndvi_plot.py --lat -21.382573 --lon 27.446793 \
    --width 40 --length 70 --bearing 140 --preview     # look at it first
python scripts/ndvi_plot.py --lat -21.382573 --lon 27.446793 \
    --width 40 --length 70 --bearing 140 --csv         # then fetch
```

## Docker

```bash
docker compose up --build          # serves on http://localhost:8000
```

## Layout

```
crop-api/
├── app/
│   ├── config.py       env-driven settings (+ tiny .env loader)
│   ├── geometry.py     pure maths: corners, area, inward inset
│   ├── cache.py        thread-safe TTL cache
│   ├── security.py     optional X-API-Key guard
│   ├── schemas.py      Pydantic request/response contracts
│   ├── gee.py          Earth Engine work + synthetic fallback + status rules
│   └── main.py         the FastAPI endpoints
├── scripts/ndvi_plot.py
├── tests/
├── requirements.txt / requirements-dev.txt
└── Dockerfile / docker-compose.yml
```
