/**
 * Weather for a plot.
 *
 * There was no weather provider in the codebase, so this module adds one on the
 * smallest possible footing: **Open-Meteo**, which needs no API key and returns
 * modelled historical + current daily data over HTTPS. That means the feature
 * works in production without another credential, and the honest label
 * (`MODELLED`, not `MEASURED`) is carried all the way to the screen.
 *
 * Rules this module obeys:
 *  - a provider failure returns `available: false` with a reason; it never
 *    substitutes zeroes or invented readings;
 *  - every value is tagged with its provider and data type;
 *  - soil moisture is converted from m³/m³ to percent, and the conversion is
 *    stated rather than hidden.
 *
 * Enable/disable with `WEATHER_ENABLED`. Base URL is overridable
 * (`WEATHER_BASE_URL`) so a different provider with the same shape can be
 * swapped in without touching callers.
 */

import { config } from '../config/index.js';
import { fetchGoogleWeather } from './googleWeather.js';

const DEFAULT_PAST_DAYS = 14;
const CACHE = new Map();

const PROVIDER = 'open-meteo';
const SOURCE = 'Open-Meteo';
const DATA_TYPE = 'MODELLED';

/**
 * Which provider answers is a deployment choice (`WEATHER_PROVIDER`). Open-Meteo
 * (default) is free and returns the multi-week archive the analysis needs; Google
 * Weather is available as an opt-in forecast source. The switch lives here so
 * every caller keeps calling `fetchWeather` and never learns which service
 * answered — the response already declares its own `provider`/`source`.
 */
const USE_GOOGLE = () => config.weather.provider === 'GOOGLE';

/** Test hook: clear the process-local weather cache. */
export function resetWeatherCache() {
  CACHE.clear();
}

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function round(value, places = 1) {
  if (value === null) return null;
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}

/**
 * A usable coordinate. `null`, `undefined` and `''` are absent, and so is
 * `NaN` — but note that `Number(null)` is `0`, so a naive numeric coercion
 * would silently accept a missing coordinate as the equator or the prime
 * meridian and fetch weather for the wrong place entirely.
 */
function hasCoordinate(value) {
  if (value === null || value === undefined || value === '') return false;
  const n = Number(value);
  if (!Number.isFinite(n)) return false;
  return Math.abs(n) <= 180;
}

function cacheKey(latitude, longitude, days) {
  return `${latitude.toFixed(3)}:${longitude.toFixed(3)}:${days}`;
}

/** Group hourly soil-moisture samples by their date and average each day. */
function soilByDate(hourly) {
  const times = Array.isArray(hourly?.time) ? hourly.time : [];
  const values = Array.isArray(hourly?.soil_moisture_0_to_7cm) ? hourly.soil_moisture_0_to_7cm : [];
  const buckets = new Map();

  times.forEach((timestamp, index) => {
    const date = String(timestamp).slice(0, 10);
    const value = finite(values[index]);
    if (value === null) return;
    const bucket = buckets.get(date) ?? [];
    bucket.push(value);
    buckets.set(date, bucket);
  });

  const result = new Map();
  buckets.forEach((bucket, date) => {
    result.set(date, (bucket.reduce((sum, value) => sum + value, 0) / bucket.length) * 100);
  });
  return result;
}

function summarise(daily, days) {
  const recent = daily.slice(-days);
  const rainfall = recent.reduce((sum, row) => sum + (row.rainfallMm ?? 0), 0);
  const maxima = recent.map((row) => row.tempMaxC).filter((value) => value !== null);
  const minima = recent.map((row) => row.tempMinC).filter((value) => value !== null);
  const means = recent.map((row) => row.tempMeanC).filter((value) => value !== null);
  const soil = recent.map((row) => row.soilMoisturePct).filter((value) => value !== null);

  return {
    periodStart: recent[0]?.date ?? null,
    periodEnd: recent[recent.length - 1]?.date ?? null,
    rainfall7dMm: round(
      recent.slice(-7).reduce((sum, row) => sum + (row.rainfallMm ?? 0), 0),
    ),
    rainfall14dMm: round(rainfall),
    tempMax7dC: maxima.length ? round(Math.max(...maxima)) : null,
    tempMin7dC: minima.length ? round(Math.min(...minima)) : null,
    avgTemp7dC: means.length ? round(means.reduce((sum, value) => sum + value, 0) / means.length) : null,
    soilMoistureMeanPct: soil.length ? round(soil.reduce((sum, value) => sum + value, 0) / soil.length) : null,
  };
}

/**
 * Fetch daily weather for a point.
 *
 * @param {{latitude:number, longitude:number, days?:number, fetchImpl?:Function, now?:number}} options
 * @returns {Promise<object>} an honest availability envelope, never throws
 */
export async function fetchWeather({
  latitude,
  longitude,
  days = DEFAULT_PAST_DAYS,
  fetchImpl = globalThis.fetch,
  now = Date.now(),
} = {}) {
  if (!config.weather.enabled) {
    return {
      available: false,
      provider: PROVIDER,
      reason: 'Weather is disabled on this server (WEATHER_ENABLED=false).',
    };
  }
  if (!hasCoordinate(latitude) || !hasCoordinate(longitude)) {
    return { available: false, provider: PROVIDER, reason: 'The plot has no centre coordinates to fetch weather for.' };
  }

  // A deployment may point weather at Google instead. The delegate returns the
  // same envelope, including its own honest `available:false` when it has no key.
  if (USE_GOOGLE()) {
    return fetchGoogleWeather({ latitude, longitude, days, fetchImpl, now });
  }

  const lat = Number(latitude);
  const lon = Number(longitude);
  const pastDays = Math.max(1, Math.min(30, Math.trunc(days)));
  const key = cacheKey(lat, lon, pastDays);
  const cached = CACHE.get(key);
  if (cached && cached.expiresAt > now) return { ...cached.value, cached: true };

  const url = new URL('/v1/forecast', config.weather.baseUrl);
  url.searchParams.set('latitude', lat.toFixed(5));
  url.searchParams.set('longitude', lon.toFixed(5));
  url.searchParams.set('daily', 'precipitation_sum,temperature_2m_max,temperature_2m_min,temperature_2m_mean');
  url.searchParams.set('hourly', 'soil_moisture_0_to_7cm');
  url.searchParams.set('past_days', String(pastDays));
  url.searchParams.set('forecast_days', '1');
  url.searchParams.set('timezone', 'auto');

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.weather.timeoutMs);

  try {
    if (typeof fetchImpl !== 'function') {
      return { available: false, provider: PROVIDER, reason: 'No HTTP client is available to fetch weather.' };
    }
    const response = await fetchImpl(url.toString(), {
      signal: controller.signal,
      headers: { accept: 'application/json', 'user-agent': 'AgriConnect/1.2 (+https://agriconnect.bw)' },
    });

    if (!response?.ok) {
      return {
        available: false,
        provider: PROVIDER,
        reason: `The weather provider returned HTTP ${response?.status ?? 'unknown'}.`,
      };
    }

    const body = await response.json();
    const dailyBody = body?.daily ?? {};
    const dates = Array.isArray(dailyBody.time) ? dailyBody.time : [];
    if (dates.length === 0) {
      return { available: false, provider: PROVIDER, reason: 'The weather provider returned no daily observations.' };
    }

    const soil = soilByDate(body?.hourly);
    const daily = dates.map((date, index) => ({
      date: String(date).slice(0, 10),
      rainfallMm: round(finite(dailyBody.precipitation_sum?.[index])),
      tempMaxC: round(finite(dailyBody.temperature_2m_max?.[index])),
      tempMinC: round(finite(dailyBody.temperature_2m_min?.[index])),
      tempMeanC: round(finite(dailyBody.temperature_2m_mean?.[index])),
      soilMoisturePct: round(soil.get(String(date).slice(0, 10))),
    }));

    const value = {
      available: true,
      provider: PROVIDER,
      source: SOURCE,
      dataType: DATA_TYPE,
      latitude: lat,
      longitude: lon,
      daily,
      summary: summarise(daily, days),
      fetchedAt: new Date(now).toISOString(),
      note:
        'Daily weather is modelled data from Open-Meteo. Soil moisture is converted from m³/m³ to percent and averaged per day. It is not a field sensor reading.',
    };

    CACHE.set(key, { value, expiresAt: now + config.weather.cacheTtlSeconds * 1000 });
    return value;
  } catch (err) {
    return {
      available: false,
      provider: PROVIDER,
      reason: err?.name === 'AbortError'
        ? 'The weather provider did not respond in time.'
        : 'The weather provider could not be reached.',
    };
  } finally {
    clearTimeout(timeout);
  }
}

export default { fetchWeather, resetWeatherCache };
