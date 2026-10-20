/**
 * Weather from the Google Weather API (Maps Platform), as an optional second
 * source alongside the default Open-Meteo provider.
 *
 * **Why this is not a drop-in replacement.** The agricultural analysis needs a
 * *series* of past days to detect a dry spell (`rainfall14dMm`), a heat spike
 * (`tempMax7dC`) or a drying trend (`soilMoistureMeanPct`). Google's historical
 * endpoint (`history.hours:lookup`) returns **24 hours**, and its daily endpoint
 * (`forecast.days:lookup`) returns a **forecast**, not a month of archive. So this
 * module supplies what Google actually has — current conditions and a forward
 * forecast — and labels what it cannot supply as unavailable rather than filling
 * it in. The caller decides how to combine that with a historical provider.
 *
 * Rules obeyed (the same as `weather.js`):
 *  - a provider failure returns `available:false` with a reason; it never
 *    substitutes zeroes or invented readings;
 *  - every value is tagged with its provider and data type;
 *  - soil moisture is **not** returned by this API, so the field is left null
 *    rather than guessed;
 *  - the API key is read from server config only and never echoed into a response.
 *
 * Enable with `WEATHER_PROVIDER=GOOGLE` and a server-side `GOOGLE_WEATHER_API_KEY`.
 * That key must be restricted (IP / API restriction) and **must not** be the key
 * shipped inside the Android app — a key in an APK is extractable by design.
 */

import { config } from '../config/index.js';

const DEFAULT_FORECAST_DAYS = 5;
const CACHE = new Map();

const PROVIDER = 'google-weather';
const SOURCE = 'Google Weather';
const DATA_TYPE = 'MODELLED';

/** Test hook: clear the process-local Google weather cache. */
export function resetGoogleWeatherCache() {
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

/** Same coordinate guard as `weather.js`: `Number(null)` is 0, so reject absent. */
function hasCoordinate(value) {
  if (value === null || value === undefined || value === '') return false;
  const n = Number(value);
  if (!Number.isFinite(n)) return false;
  return Math.abs(n) <= 180;
}

function cacheKey(latitude, longitude, days) {
  return `${latitude.toFixed(3)}:${longitude.toFixed(3)}:${days}`;
}

/** A `{degrees}` wrapper from the API, or null. */
function degrees(wrapper) {
  return round(finite(wrapper?.degrees));
}

/** ISO date (UTC) from a `displayDate` part, falling back to the interval start. */
function dateOf(day) {
  const display = day?.displayDate;
  if (display?.year && display?.month && display?.day) {
    const month = String(display.month).padStart(2, '0');
    const dayOfMonth = String(display.day).padStart(2, '0');
    return `${display.year}-${month}-${dayOfMonth}`;
  }
  const start = day?.interval?.startTime;
  return start ? String(start).slice(0, 10) : null;
}

/** Millimetres of rain for one forecast day, summed across day and night. */
function rainMm(day) {
  const parts = [day?.daytimeForecast, day?.nighttimeForecast];
  const values = parts
    .map((part) => finite(part?.precipitation?.qpf?.quantity))
    .filter((value) => value !== null);
  if (values.length === 0) return null;
  return round(values.reduce((sum, value) => sum + value, 0));
}

/**
 * Map Google's `forecastDays` array into the provider-neutral `daily` rows the
 * rest of the app already understands. A field Google does not return (soil
 * moisture) stays null — it is never defaulted to zero.
 */
export function mapForecastDays(forecastDays) {
  const days = Array.isArray(forecastDays) ? forecastDays : [];
  return days
    .map((day) => {
      const date = dateOf(day);
      if (!date) return null;
      const max = degrees(day?.maxTemperature);
      const min = degrees(day?.minTemperature);
      return {
        date,
        rainfallMm: rainMm(day),
        tempMaxC: max,
        tempMinC: min,
        // Google gives max/min, not a mean; the midpoint is stated as derived
        // rather than presented as a measured daily mean.
        tempMeanC: max !== null && min !== null ? round((max + min) / 2) : null,
        // Not provided by this API. Left null on purpose.
        soilMoisturePct: null,
      };
    })
    .filter(Boolean);
}

function summarise(daily) {
  const maxima = daily.map((row) => row.tempMaxC).filter((value) => value !== null);
  const minima = daily.map((row) => row.tempMinC).filter((value) => value !== null);
  const rain = daily.map((row) => row.rainfallMm).filter((value) => value !== null);

  return {
    periodStart: daily[0]?.date ?? null,
    periodEnd: daily[daily.length - 1]?.date ?? null,
    rainfall7dMm: rain.length ? round(rain.reduce((sum, value) => sum + value, 0)) : null,
    rainfall14dMm: null,
    tempMax7dC: maxima.length ? round(Math.max(...maxima)) : null,
    tempMin7dC: minima.length ? round(Math.min(...minima)) : null,
    avgTemp7dC: null,
    soilMoistureMeanPct: null,
  };
}

/**
 * Fetch current conditions plus the daily forecast for a point.
 *
 * @param {{latitude:number, longitude:number, days?:number, fetchImpl?:Function, now?:number}} options
 * @returns {Promise<object>} an honest availability envelope, never throws
 */
export async function fetchGoogleWeather({
  latitude,
  longitude,
  days = DEFAULT_FORECAST_DAYS,
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
  if (!config.weather.googleApiKey) {
    return {
      available: false,
      provider: PROVIDER,
      reason: 'No Google Weather API key is configured on this server (GOOGLE_WEATHER_API_KEY).',
    };
  }
  if (!hasCoordinate(latitude) || !hasCoordinate(longitude)) {
    return { available: false, provider: PROVIDER, reason: 'The plot has no centre coordinates to fetch weather for.' };
  }

  const lat = Number(latitude);
  const lon = Number(longitude);
  // Google returns up to 10 forecast days; clamp to that so the request is valid.
  const forecastDays = Math.max(1, Math.min(10, Math.trunc(days)));
  const key = cacheKey(lat, lon, forecastDays);
  const cached = CACHE.get(key);
  if (cached && cached.expiresAt > now) return { ...cached.value, cached: true };

  const url = new URL('/v1/forecast/days:lookup', config.weather.googleBaseUrl);
  url.searchParams.set('key', config.weather.googleApiKey);
  url.searchParams.set('location.latitude', lat.toFixed(5));
  url.searchParams.set('location.longitude', lon.toFixed(5));
  url.searchParams.set('days', String(forecastDays));
  url.searchParams.set('pageSize', String(forecastDays));
  url.searchParams.set('unitsSystem', 'METRIC');

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
      // Never leak the response body: it can echo the request URL, and the URL
      // carries the API key.
      return {
        available: false,
        provider: PROVIDER,
        reason: `Google Weather returned HTTP ${response?.status ?? 'unknown'}.`,
      };
    }

    const body = await response.json();
    const daily = mapForecastDays(body?.forecastDays);
    if (daily.length === 0) {
      return {
        available: false,
        provider: PROVIDER,
        reason: 'Google Weather returned no forecast days for this location.',
      };
    }

    const value = {
      available: true,
      provider: PROVIDER,
      source: SOURCE,
      dataType: DATA_TYPE,
      latitude: lat,
      longitude: lon,
      timeZone: body?.timeZone?.id ?? null,
      daily,
      summary: summarise(daily),
      fetchedAt: new Date(now).toISOString(),
      note:
        'Forward forecast from Google Weather API (modelled). Google supplies a forecast and 24 hours of history, '
        + 'not a long archive, so no 14-day rainfall or soil-moisture figure is provided here rather than guessed. '
        + 'Daily mean temperature is the midpoint of the published max/min.',
    };

    CACHE.set(key, { value, expiresAt: now + config.weather.cacheTtlSeconds * 1000 });
    return value;
  } catch (err) {
    return {
      available: false,
      provider: PROVIDER,
      reason: err?.name === 'AbortError'
        ? 'Google Weather did not respond in time.'
        : 'Google Weather could not be reached.',
    };
  } finally {
    clearTimeout(timeout);
  }
}

export default { fetchGoogleWeather, resetGoogleWeatherCache, mapForecastDays };
