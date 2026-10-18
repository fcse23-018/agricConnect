"""End-to-end HTTP tests against the FastAPI app (synthetic GEE mode)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import config, gee
from app.main import app

CENTRE_BODY = {
    "centre": {"lat": -21.382573, "lon": 27.446793},
    "width_m": 40,
    "length_m": 70,
    "bearing_deg": 140,
}


@pytest.fixture(autouse=True)
def _clean_state():
    gee.reset_cache()
    yield
    gee.reset_cache()


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


def test_health_reports_mode(client):
    body = client.get("/health").json()
    assert body["ok"] is True
    assert body["service"] == "agriconnect-crop-monitoring"
    assert body["gee_enabled"] is False


def test_indices_returns_a_series(client):
    response = client.post("/v1/indices", json=CENTRE_BODY)
    assert response.status_code == 200
    body = response.json()
    assert body["source"] == "mock"
    assert body["area_m2"] == pytest.approx(1800, rel=0.03)
    assert len(body["readings"]) > 20
    assert body["min_pixels"] >= 3
    for reading in body["readings"]:
        assert 0.0 <= reading["ndvi"] <= 1.0
        assert reading["pixels"] >= body["min_pixels"]


def test_health_summary_reports_a_status(client):
    response = client.post("/v1/health-summary", json=CENTRE_BODY)
    assert response.status_code == 200
    summary = response.json()["summary"]
    assert summary["state"] in {
        "rising",
        "stable",
        "declining",
        "watch",
        "bare_or_fallow",
        "no_data",
    }
    assert summary["data_age_days"] is not None


def test_area_endpoint_needs_no_satellite_data(client):
    response = client.post("/v1/area", json=CENTRE_BODY)
    assert response.status_code == 200
    body = response.json()
    assert body["area_m2"] == pytest.approx(1800, rel=0.03)
    assert body["outer"]["type"] == "Polygon"
    assert body["measured"]["type"] == "Polygon"


def test_corners_form_is_accepted(client):
    body = {
        "corners": [
            {"lat": -21.38250, "lon": 27.44660},
            {"lat": -21.38250, "lon": 27.44700},
            {"lat": -21.38285, "lon": 27.44700},
            {"lat": -21.38285, "lon": 27.44660},
        ],
        "inset_m": 5,
    }
    assert client.post("/v1/indices", json=body).status_code == 200


def test_both_forms_returns_422(client):
    body = dict(CENTRE_BODY, corners=[{"lat": 0, "lon": 0}] * 3)
    assert client.post("/v1/indices", json=body).status_code == 422


def test_neither_form_returns_422(client):
    assert client.post("/v1/indices", json={"inset_m": 5}).status_code == 422


def test_tiny_area_returns_422(client):
    body = {"centre": {"lat": -21.38, "lon": 27.44}, "width_m": 6, "length_m": 6, "inset_m": 5}
    response = client.post("/v1/health-summary", json=body)
    assert response.status_code == 422
    assert "200 m2" in response.json()["detail"]


def test_api_key_is_enforced_when_configured(client, monkeypatch):
    monkeypatch.setenv("CROP_API_KEY", "s3cret-key")
    config.reload()
    try:
        assert client.post("/v1/indices", json=CENTRE_BODY).status_code == 401
        ok = client.post("/v1/indices", json=CENTRE_BODY, headers={"X-API-Key": "s3cret-key"})
        assert ok.status_code == 200
        assert client.get("/health").status_code == 200  # probe stays open
    finally:
        monkeypatch.delenv("CROP_API_KEY", raising=False)
        config.reload()


def test_same_plot_is_deterministic(client):
    first = client.post("/v1/health-summary", json=CENTRE_BODY).json()
    gee.reset_cache()
    second = client.post("/v1/health-summary", json=CENTRE_BODY).json()
    assert first["summary"]["latest"] == second["summary"]["latest"]


def test_different_plots_differ(client):
    other = {
        "centre": {"lat": -24.65, "lon": 25.91},
        "width_m": 60,
        "length_m": 90,
    }
    a = client.post("/v1/indices", json=CENTRE_BODY).json()["readings"]
    b = client.post("/v1/indices", json=other).json()["readings"]
    assert [r["ndvi"] for r in a] != [r["ndvi"] for r in b]
