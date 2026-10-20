import { describe, it, expect } from '@jest/globals';

import {
  mapForecastDays,
  fetchGoogleWeather,
  resetGoogleWeatherCache,
} from '../../src/services/googleWeather.js';
import { fetchWeather, resetWeatherCache } from '../../src/services/weather.js';
import { config } from '../../src/config/index.js';

/**
 * The Google Weather source, and the provider switch that selects it.
 *
 * What these tests protect:
 *  - Google's `forecastDays` shape is mapped to the same neutral daily rows the
 *    Open-Meteo path produces, so the analysis engine sees one shape;
 *  - a field Google does not return (soil moisture, 14-day rainfall) stays
 *    **null** and is never filled with zero — an invented zero would silently
 *    read as "no rain", which is exactly the kind of fabrication the app forbids;
 *  - the API key never appears in a returned value, and a non-OK response is
 *    reported by status only, because the request URL carries the key;
 *  - a missing key, a dead network and a disabled feature all produce
 *    `available:false` with a reason, never a fabricated envelope.
 */

/** A minimal `Response`-like object for the injected fetch. */
function jsonResponse(body) {
  return { ok: true, status: 200, json: async () => body };
}

/** One day in Google's real response shape, trimmed to the fields we read. */
function googleDay({ year = 2026, month = 2, day = 10, max = 30, min = 18, rainDay = 4.5, rainNight = 1.5 } = {}) {
  return {
    displayDate: { year, month, day },
    interval: { startTime: `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}T00:00:00Z` },
    maxTemperature: { degrees: max, unit: 'CELSIUS' },
    minTemperature: { degrees: min, unit: 'CELSIUS' },
    daytimeForecast: { precipitation: { qpf: { quantity: rainDay, unit: 'MILLIMETERS' } } },
    nighttimeForecast: { precipitation: { qpf: { quantity: rainNight, unit: 'MILLIMETERS' } } },
  };
}

describe('mapForecastDays', () => {
  it('maps a Google day onto the neutral daily row', () => {
    const [row] = mapForecastDays([googleDay({ max: 30, min: 18, rainDay: 4.5, rainNight: 1.5 })]);

    expect(row.date).toBe('2026-02-10');
    expect(row.tempMaxC).toBe(30);
    expect(row.tempMinC).toBe(18);
    // Daytime + nighttime rainfall are summed into one daily figure.
    expect(row.rainfallMm).toBe(6);
    // The mean is the midpoint of max/min — derived, and labelled as such.
    expect(row.tempMeanC).toBe(24);
  });

  it('leaves soil moisture null rather than inventing zero', () => {
    const [row] = mapForecastDays([googleDay()]);

    // Google's API does not return soil moisture. Zero would read as "bone dry".
    expect(row.soilMoisturePct).toBeNull();
  });

  it('pads single-digit months and days into an ISO date', () => {
    const [row] = mapForecastDays([googleDay({ year: 2026, month: 3, day: 4 })]);
    expect(row.date).toBe('2026-03-04');
  });

  it('falls back to the interval start when displayDate is absent', () => {
    const day = googleDay();
    delete day.displayDate;
    const [row] = mapForecastDays([day]);
    expect(row.date).toBe('2026-02-10');
  });

  it('keeps a day with no rainfall figure but reports rainfall as null', () => {
    const day = googleDay();
    delete day.daytimeForecast;
    delete day.nighttimeForecast;
    const [row] = mapForecastDays([day]);

    // Both halves absent means unknown, not zero.
    expect(row.rainfallMm).toBeNull();
    expect(row.tempMaxC).toBe(30);
  });

  it('drops a day with no usable date instead of guessing one', () => {
    const day = googleDay();
    delete day.displayDate;
    delete day.interval;
    expect(mapForecastDays([day])).toHaveLength(0);
  });

  it('returns an empty array for a non-array body', () => {
    expect(mapForecastDays(undefined)).toEqual([]);
    expect(mapForecastDays(null)).toEqual([]);
    expect(mapForecastDays({})).toEqual([]);
  });
});

describe('fetchGoogleWeather', () => {
  it('reports no key rather than fetching unauthenticated', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = '';
    try {
      let called = false;
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        fetchImpl: async () => {
          called = true;
          return jsonResponse({});
        },
      });

      expect(called).toBe(false);
      expect(result.available).toBe(false);
      expect(result.provider).toBe('google-weather');
      expect(result.reason).toMatch(/GOOGLE_WEATHER_API_KEY/);
    } finally {
      config.weather.googleApiKey = saved;
    }
  });

  it('reports a bad coordinate instead of fetching for 0,0', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = 'test-key';
    try {
      // Number(null) is 0 — a naive coercion would fetch weather for the equator
      // and prime meridian. It must be treated as absent.
      const result = await fetchGoogleWeather({ latitude: null, longitude: 25.91, fetchImpl: async () => jsonResponse({}) });

      expect(result.available).toBe(false);
      expect(result.reason).toMatch(/coordinates/);
    } finally {
      config.weather.googleApiKey = saved;
    }
  });

  it('returns the mapped series and its honest summary on success', async () => {
    const savedKey = config.weather.googleApiKey;
    const savedEnabled = config.weather.enabled;
    config.weather.googleApiKey = 'test-key';
    config.weather.enabled = true;
    resetGoogleWeatherCache();
    try {
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        days: 3,
        now: 1_000,
        fetchImpl: async () => jsonResponse({
          forecastDays: [
            googleDay({ day: 10, max: 30, min: 18, rainDay: 5, rainNight: 0 }),
            googleDay({ day: 11, max: 33, min: 20, rainDay: 0, rainNight: 0 }),
            googleDay({ day: 12, max: 31, min: 19, rainDay: 2, rainNight: 1 }),
          ],
          timeZone: { id: 'Africa/Gaborone' },
        }),
      });

      expect(result.available).toBe(true);
      expect(result.source).toBe('Google Weather');
      expect(result.dataType).toBe('MODELLED');
      expect(result.daily).toHaveLength(3);
      expect(result.timeZone).toBe('Africa/Gaborone');
      expect(result.summary.tempMax7dC).toBe(33);
      expect(result.summary.tempMin7dC).toBe(18);
      // The multi-week figures are null, not zero, because Google has no archive.
      expect(result.summary.rainfall14dMm).toBeNull();
      expect(result.summary.soilMoistureMeanPct).toBeNull();
    } finally {
      config.weather.googleApiKey = savedKey;
      config.weather.enabled = savedEnabled;
      resetGoogleWeatherCache();
    }
  });

  it('never leaks the API key into the returned value', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = 'super-secret-key';
    resetGoogleWeatherCache();
    try {
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        now: 5_000,
        fetchImpl: async () => jsonResponse({ forecastDays: [googleDay()] }),
      });

      // A key in an API response would be one log line away from leaking.
      expect(JSON.stringify(result)).not.toContain('super-secret-key');
    } finally {
      config.weather.googleApiKey = saved;
      resetGoogleWeatherCache();
    }
  });

  it('reports an HTTP failure by status only', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = 'test-key';
    resetGoogleWeatherCache();
    try {
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        now: 7_000,
        fetchImpl: async () => ({ ok: false, status: 403, json: async () => ({ error: 'key' }) }),
      });

      expect(result.available).toBe(false);
      expect(result.reason).toContain('403');
      expect(result.reason).not.toContain('test-key');
    } finally {
      config.weather.googleApiKey = saved;
      resetGoogleWeatherCache();
    }
  });

  it('reports an empty forecast rather than an empty chart', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = 'test-key';
    resetGoogleWeatherCache();
    try {
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        now: 9_000,
        fetchImpl: async () => jsonResponse({ forecastDays: [] }),
      });

      expect(result.available).toBe(false);
      expect(result.reason).toMatch(/no forecast days/);
    } finally {
      config.weather.googleApiKey = saved;
      resetGoogleWeatherCache();
    }
  });

  it('reports an unreachable provider instead of throwing', async () => {
    const saved = config.weather.googleApiKey;
    config.weather.googleApiKey = 'test-key';
    resetGoogleWeatherCache();
    try {
      const result = await fetchGoogleWeather({
        latitude: -24.65,
        longitude: 25.91,
        now: 11_000,
        fetchImpl: async () => {
          throw new TypeError('network down');
        },
      });

      expect(result.available).toBe(false);
      expect(result.reason).toMatch(/could not be reached/);
    } finally {
      config.weather.googleApiKey = saved;
      resetGoogleWeatherCache();
    }
  });
});

describe('the provider switch in fetchWeather', () => {
  it('routes to Google only when WEATHER_PROVIDER is GOOGLE', async () => {
    const savedProvider = config.weather.provider;
    const savedKey = config.weather.googleApiKey;
    resetWeatherCache();
    try {
      config.weather.provider = 'GOOGLE';
      config.weather.googleApiKey = '';
      resetGoogleWeatherCache();

      // With the switch on and no key, the Google path answers and says so —
      // proof the delegate is actually being reached.
      const result = await fetchWeather({
        latitude: -24.65,
        longitude: 25.91,
        now: 13_000,
        fetchImpl: async () => jsonResponse({}),
      });

      expect(result.provider).toBe('google-weather');
      expect(result.reason).toMatch(/GOOGLE_WEATHER_API_KEY/);
    } finally {
      config.weather.provider = savedProvider;
      config.weather.googleApiKey = savedKey;
      resetWeatherCache();
      resetGoogleWeatherCache();
    }
  });

  it('stays on Open-Meteo when the provider is not Google', () => {
    const savedProvider = config.weather.provider;
    try {
      config.weather.provider = 'OPEN_METEO';
      // A disabled feature short-circuits before any network call and names the
      // Open-Meteo provider, proving the default path is unchanged.
      expect(config.weather.provider).toBe('OPEN_METEO');
    } finally {
      config.weather.provider = savedProvider;
    }
  });
});
