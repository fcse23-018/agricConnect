import { describe, it, expect, beforeEach, afterEach } from '@jest/globals';

import { fetchWeather, resetWeatherCache } from '../../src/services/weather.js';
import { config } from '../../src/config/index.js';

/** A minimal Open-Meteo-shaped response. */
function openMeteoBody({ days = 5, rainfall = 2, tempMax = 30, tempMin = 15, soil = 0.18 } = {}) {
  const dates = Array.from({ length: days }, (_, i) =>
    new Date(Date.UTC(2026, 2, 1 + i)).toISOString().slice(0, 10));
  return {
    daily: {
      time: dates,
      precipitation_sum: dates.map(() => rainfall),
      temperature_2m_max: dates.map(() => tempMax),
      temperature_2m_min: dates.map(() => tempMin),
      temperature_2m_mean: dates.map(() => (tempMax + tempMin) / 2),
    },
    hourly: {
      time: dates.flatMap((date) => [`${date}T00:00`, `${date}T12:00`]),
      soil_moisture_0_to_7cm: dates.flatMap(() => [soil, soil]),
    },
  };
}

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return { ok, status, json: async () => body };
}

const originalEnabled = config.weather.enabled;

describe('fetchWeather', () => {
  beforeEach(() => {
    resetWeatherCache();
    config.weather.enabled = true;
  });
  afterEach(() => {
    config.weather.enabled = originalEnabled;
  });

  it('returns labelled modelled daily data with a summary', async () => {
    const result = await fetchWeather({
      latitude: -24.628,
      longitude: 25.923,
      days: 5,
      fetchImpl: async () => jsonResponse(openMeteoBody({ days: 5, rainfall: 2, tempMax: 34, tempMin: 12 })),
    });

    expect(result.available).toBe(true);
    expect(result.source).toBe('Open-Meteo');
    expect(result.dataType).toBe('MODELLED');
    expect(result.daily).toHaveLength(5);
    expect(result.daily[0]).toEqual({
      date: '2026-03-01',
      rainfallMm: 2,
      tempMaxC: 34,
      tempMinC: 12,
      tempMeanC: 23,
      soilMoisturePct: 18,
    });
    expect(result.summary.rainfall14dMm).toBe(10);
    expect(result.summary.rainfall7dMm).toBe(10);
    expect(result.summary.tempMax7dC).toBe(34);
    expect(result.summary.tempMin7dC).toBe(12);
    expect(result.summary.soilMoistureMeanPct).toBe(18);
    expect(result.note).toMatch(/modelled data/);
  });

  it('converts soil moisture from m³/m³ to percent and averages per day', async () => {
    const body = openMeteoBody({ days: 1 });
    body.hourly = {
      time: ['2026-03-01T00:00', '2026-03-01T06:00', '2026-03-01T12:00'],
      soil_moisture_0_to_7cm: [0.1, 0.2, null],
    };
    const result = await fetchWeather({
      latitude: -24.6,
      longitude: 25.9,
      days: 1,
      fetchImpl: async () => jsonResponse(body),
    });
    // (0.1 + 0.2) / 2 = 0.15 m³/m³ → 15%
    expect(result.daily[0].soilMoisturePct).toBe(15);
  });

  it('requests the documented Open-Meteo fields over HTTPS', async () => {
    let requested = null;
    await fetchWeather({
      latitude: -24.6284,
      longitude: 25.9231,
      days: 14,
      fetchImpl: async (url) => {
        requested = new URL(url);
        return jsonResponse(openMeteoBody());
      },
    });
    expect(requested.protocol).toBe('https:');
    expect(requested.pathname).toBe('/v1/forecast');
    expect(requested.searchParams.get('latitude')).toBe('-24.62840');
    expect(requested.searchParams.get('longitude')).toBe('25.92310');
    expect(requested.searchParams.get('past_days')).toBe('14');
    expect(requested.searchParams.get('daily')).toContain('precipitation_sum');
    expect(requested.searchParams.get('hourly')).toBe('soil_moisture_0_to_7cm');
  });

  it('reports unavailability instead of inventing values when disabled', async () => {
    config.weather.enabled = false;
    const result = await fetchWeather({ latitude: -24, longitude: 25, fetchImpl: async () => jsonResponse({}) });
    expect(result.available).toBe(false);
    expect(result.reason).toMatch(/WEATHER_ENABLED=false/);
    expect(result.daily).toBeUndefined();
  });

  it('reports unavailability when the plot has no coordinates', async () => {
    const result = await fetchWeather({ latitude: null, longitude: null, fetchImpl: async () => jsonResponse({}) });
    expect(result.available).toBe(false);
    expect(result.reason).toMatch(/no centre coordinates/);
  });

  it('reports an HTTP failure rather than zeroes', async () => {
    const result = await fetchWeather({
      latitude: -24,
      longitude: 25,
      fetchImpl: async () => jsonResponse({}, { ok: false, status: 503 }),
    });
    expect(result.available).toBe(false);
    expect(result.reason).toContain('503');
  });

  it('reports an empty provider body as unavailable', async () => {
    const result = await fetchWeather({
      latitude: -24,
      longitude: 25,
      fetchImpl: async () => jsonResponse({ daily: { time: [] } }),
    });
    expect(result.available).toBe(false);
    expect(result.reason).toMatch(/no daily observations/);
  });

  it('reports a network failure rather than throwing', async () => {
    const result = await fetchWeather({
      latitude: -24,
      longitude: 25,
      fetchImpl: async () => {
        throw new Error('socket hang up');
      },
    });
    expect(result.available).toBe(false);
    expect(result.reason).toMatch(/could not be reached/);
  });

  it('serves the second call for the same point from the process cache', async () => {
    let calls = 0;
    const fetchImpl = async () => {
      calls += 1;
      return jsonResponse(openMeteoBody());
    };
    const first = await fetchWeather({ latitude: -24.5, longitude: 25.5, days: 7, fetchImpl, now: 1_000_000 });
    const second = await fetchWeather({ latitude: -24.5, longitude: 25.5, days: 7, fetchImpl, now: 1_001_000 });

    expect(calls).toBe(1);
    expect(first.cached).toBeUndefined();
    expect(second.cached).toBe(true);
    expect(second.daily).toEqual(first.daily);
  });

  it('does not serve an expired cache entry', async () => {
    let calls = 0;
    const fetchImpl = async () => {
      calls += 1;
      return jsonResponse(openMeteoBody());
    };
    await fetchWeather({ latitude: -24.5, longitude: 25.5, days: 7, fetchImpl, now: 1_000_000 });
    await fetchWeather({
      latitude: -24.5,
      longitude: 25.5,
      days: 7,
      fetchImpl,
      now: 1_000_000 + config.weather.cacheTtlSeconds * 1000 + 1,
    });
    expect(calls).toBe(2);
  });
});
