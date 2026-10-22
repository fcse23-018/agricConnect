import 'dotenv/config';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';

function bool(v, def = false) {
  if (v === undefined || v === '') return def;
  return ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase());
}
function int(v, def) {
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : def;
}
function num(v, def) {
  if (v === undefined || v === null || v === '') return def;
  const n = Number(v);
  return Number.isFinite(n) ? n : def;
}

/**
 * Resolve the Earth Engine service-account credentials.
 *
 * Earth Engine rejects API keys outright ("API keys are not supported by this
 * API") — it needs a service-account private key. Operators supply that in the
 * shape that suits their platform:
 *
 *   GEE_KEY_FILE — a path to the downloaded JSON key. On Render this is a secret
 *                  file mount, which keeps the PEM out of the environment dump.
 *   GEE_KEY_JSON — the whole JSON as a single-line env var, for hosts without
 *                  file mounts.
 *   GEE_PRIVATE_KEY / GEE_SERVICE_ACCOUNT_EMAIL — the raw fields, kept for
 *                  backwards compatibility with earlier deployments.
 *
 * All failures are returned as a reason rather than thrown: a malformed key must
 * degrade to the labelled MOCK path with an explanation, never crash the API at
 * import time. `used` records which source answered, for the startup log.
 */
function resolveGeeCredentials() {
  const result = {
    serviceAccountEmail: process.env.GEE_SERVICE_ACCOUNT_EMAIL || '',
    privateKey: (process.env.GEE_PRIVATE_KEY || '').replace(/\\n/g, '\n'),
    projectId: process.env.GEE_PROJECT_ID || '',
    used: null,
    problem: null,
  };

  const fromRaw = () => {
    result.used = 'env';
    return true;
  };

  const applyJson = (raw, source) => {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      result.problem = `${source} is not valid JSON`;
      return false;
    }
    if (parsed.type && parsed.type !== 'service_account') {
      result.problem = `${source} is not a service-account key (type "${parsed.type}")`;
      return false;
    }
    result.serviceAccountEmail = parsed.client_email || result.serviceAccountEmail;
    result.privateKey = parsed.private_key || result.privateKey;
    result.projectId = parsed.project_id || result.projectId;
    result.used = source;
    return true;
  };

  if (process.env.GEE_KEY_FILE) {
    let raw;
    try {
      raw = readFileSync(process.env.GEE_KEY_FILE, 'utf8');
    } catch (err) {
      result.problem = `GEE_KEY_FILE could not be read (${err.code || err.message})`;
      return result;
    }
    if (applyJson(raw, 'file')) return result;
    return result;
  }

  if (process.env.GEE_KEY_JSON) {
    if (applyJson(process.env.GEE_KEY_JSON, 'GEE_KEY_JSON')) return result;
    return result;
  }

  if (result.privateKey) {
    fromRaw();
    return result;
  }

  // Nothing supplied. Not an error — GEE is simply off.
  return result;
}

const geeCreds = resolveGeeCredentials();

const env = process.env.NODE_ENV || 'development';

export const config = {
  env,
  isProd: env === 'production',
  isTest: env === 'test',
  port: int(process.env.PORT, 4000),
  publicBaseUrl: process.env.PUBLIC_BASE_URL || 'http://localhost:4000',
  corsOrigins: (process.env.CORS_ORIGINS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),

  db: {
    connectionString: process.env.DATABASE_URL || undefined,
    host: process.env.PGHOST || 'localhost',
    port: int(process.env.PGPORT, 5432),
    database: process.env.PGDATABASE || 'agriconnect',
    user: process.env.PGUSER || 'agriconnect',
    password: process.env.PGPASSWORD || '',
    ssl: (process.env.PGSSLMODE || 'disable') !== 'disable' ? { rejectUnauthorized: false } : false,
  },

  jwt: {
    accessSecret: process.env.JWT_ACCESS_SECRET || 'dev-only-access-secret-change-me',
    refreshSecret: process.env.JWT_REFRESH_SECRET || 'dev-only-refresh-secret-change-me',
    accessTtl: process.env.ACCESS_TOKEN_TTL || '15m',
    refreshTtlDays: int(process.env.REFRESH_TOKEN_TTL_DAYS, 30),
  },

  tokens: {
    passwordResetTtlMinutes: int(process.env.PASSWORD_RESET_TTL_MINUTES, 15),
    verificationCodeTtlMinutes: int(process.env.VERIFICATION_CODE_TTL_MINUTES, 10),
    maxVerificationAttempts: 5,
  },

  account: {
    deletionGraceDays: int(process.env.ACCOUNT_DELETION_GRACE_DAYS, 14),
    financialRetentionMonths: int(process.env.FINANCIAL_RETENTION_MONTHS, 84),
  },

  delivery: {
    provider: (process.env.DELIVERY_PROVIDER || 'NONE').toUpperCase(), // NONE|OUTBOX|SMTP|TWILIO
    exposeDevCodes: bool(process.env.EXPOSE_DEV_CODES, false) && env !== 'production',
    /**
     * Explicit acknowledgement that production runs with no working transport.
     *
     * Required rather than implicit because the alternative — silently pretending a
     * recovery code was sent — is exactly the failure the brief forbids. See
     * `assertProdSecrets()`.
     */
    allowGap: bool(process.env.ALLOW_DELIVERY_GAP, false),
    smtp: {
      host: process.env.SMTP_HOST || '',
      port: int(process.env.SMTP_PORT, 587),
      user: process.env.SMTP_USER || '',
      pass: process.env.SMTP_PASS || '',
    },
    mailFrom: process.env.MAIL_FROM || 'AgriConnect <no-reply@agriconnect.bw>',
    twilio: {
      accountSid: process.env.TWILIO_ACCOUNT_SID || '',
      authToken: process.env.TWILIO_AUTH_TOKEN || '',
      from: process.env.TWILIO_FROM || '',
    },
  },

  gee: {
    enabled: bool(process.env.GEE_ENABLED, false),
    serviceAccountEmail: geeCreds.serviceAccountEmail,
    privateKey: geeCreds.privateKey,
    projectId: geeCreds.projectId,
    /** Which source supplied the credentials: file | GEE_KEY_JSON | env | null. */
    credentialSource: geeCreds.used,
    /** Why credential loading failed, if it did. Surfaces as a plain reason. */
    credentialProblem: geeCreds.problem,
    cloudCoverFailThreshold: int(process.env.NDVI_CLOUD_COVER_FAIL_THRESHOLD, 80),
    lookbackDays: int(process.env.GEE_LOOKBACK_DAYS, 60),
  },

  /**
   * Optional FastAPI crop-monitoring service (`crop-api/`).
   *
   * When configured, the Node API asks it for the real NDVI/EVI series
   * (Sentinel-2 with cloud masking). When it is not, the Node API falls back to
   * a deterministic, clearly-labelled MOCK series so the feature can be
   * exercised end to end. The fallback is never presented as a real observation.
   */
  cropApi: {
    enabled: bool(process.env.CROP_API_ENABLED, false),
    url: (process.env.CROP_API_URL || '').replace(/\/+$/, ''),
    apiKey: process.env.CROP_API_KEY || '',
    timeoutMs: int(process.env.CROP_API_TIMEOUT_MS, 30_000),
  },

  /**
   * Weather has no credentials: Open-Meteo is free, HTTPS and needs no key.
   * `WEATHER_ENABLED=false` disables it and the API then says so instead of
   * inventing readings.
   */
  weather: {
    enabled: bool(process.env.WEATHER_ENABLED, true),
    baseUrl: (process.env.WEATHER_BASE_URL || 'https://api.open-meteo.com').replace(/\/+$/, ''),
    timeoutMs: int(process.env.WEATHER_TIMEOUT_MS, 8_000),
    cacheTtlSeconds: int(process.env.WEATHER_CACHE_TTL_SECONDS, 1_800),

    /**
     * Which provider answers. `OPEN_METEO` (default, free, no key) or `GOOGLE`.
     * Google Weather is metered and needs a *server-side* key — it must never be
     * the same key shipped in the Android app, because a key inside an APK is
     * extractable. Keep this one restricted by IP in Google Cloud Console.
     */
    provider: (process.env.WEATHER_PROVIDER || 'OPEN_METEO').trim().toUpperCase(),
    googleApiKey: process.env.GOOGLE_WEATHER_API_KEY || '',
    googleBaseUrl: (process.env.GOOGLE_WEATHER_BASE_URL || 'https://weather.googleapis.com').replace(/\/+$/, ''),
  },

  /**
   * Analysis thresholds. Documented in `docs/12-SATELLITE-MONITORING.md`; every
   * one is overridable so a deployment can tune them without a code change.
   */
  satellite: {
    defaultDays: int(process.env.SATELLITE_DEFAULT_DAYS, 120),
    maxDays: int(process.env.SATELLITE_MAX_DAYS, 365),
    refreshHours: int(process.env.SATELLITE_REFRESH_HOURS, 6),
    dropAbsolute: num(process.env.SATELLITE_DROP_ABSOLUTE, 0.15),
    dropRelative: num(process.env.SATELLITE_DROP_RELATIVE, 0.15),
    staleDays: int(process.env.SATELLITE_STALE_DAYS, 10),
    stressNdvi: num(process.env.SATELLITE_STRESS_NDVI, 0.3),
    noRecoveryDays: int(process.env.SATELLITE_NO_RECOVERY_DAYS, 21),
    dryRain14dMm: num(process.env.SATELLITE_DRY_RAIN_14D_MM, 10),
    heatTempMaxC: num(process.env.SATELLITE_HEAT_TEMP_MAX_C, 32),
  },

  /** APK update manifest served at `GET /api/v1/app/version`. */
  updates: {
    enabled: bool(process.env.APP_UPDATES_ENABLED, true),
    /**
     * Default is the repository's `version-manifest.json`.
     *
     * `fileURLToPath` rather than `new URL(...).pathname`, because on Windows the
     * latter produces `/C:/...`, which `fs` then fails to open — the endpoint
     * would silently report "no manifest" on a developer machine while working
     * on Linux. This was exactly that bug.
     */
    manifestPath: process.env.APP_VERSION_MANIFEST || fileURLToPath(new URL('../../version-manifest.json', import.meta.url)),
    downloadUrl: process.env.APK_DOWNLOAD_URL || '',
    packageName: process.env.APK_PACKAGE_NAME || 'bw.agriconnect',
    releaseNotesUrl: process.env.APP_RELEASE_NOTES_URL || '',
  },

  uploads: {
    /**
     * Where stored images live.
     *
     * `local` writes to disk and serves from `/uploads` — correct for a host with a
     * persistent filesystem (your own PC, a VM, docker-compose).
     *
     * `supabase` writes to Supabase Storage instead. This is what a free PaaS needs:
     * Render's free tier has an **ephemeral** filesystem, so anything written to disk
     * is destroyed on every redeploy and every spindown — an uploaded avatar would
     * silently disappear. Object storage survives all of that. The URL returned is
     * origin-relative in both cases, so the database never changes shape.
     */
    driver: (process.env.UPLOAD_DRIVER || 'local').toLowerCase(), // local|supabase
    dir: process.env.UPLOAD_DIR || './uploads',
    maxBytes: int(process.env.MAX_UPLOAD_BYTES, 2 * 1024 * 1024),
    supabaseUrl: (process.env.SUPABASE_URL || '').replace(/\/+$/, ''),
    supabaseServiceRoleKey: process.env.SUPABASE_SERVICE_ROLE_KEY || '',
    supabaseBucket: process.env.SUPABASE_STORAGE_BUCKET || 'agriconnect-uploads',
  },

  rateLimit: {
    authMax: int(process.env.RATE_LIMIT_AUTH_MAX, 10),
    authWindowMs: int(process.env.RATE_LIMIT_AUTH_WINDOW_MINUTES, 15) * 60_000,
    syncMax: int(process.env.RATE_LIMIT_SYNC_MAX, 120),
    publicMax: int(process.env.RATE_LIMIT_PUBLIC_MAX, 300),
  },

  logLevel: process.env.LOG_LEVEL || 'info',
};

export { num as numberFromEnv };

export function assertProdSecrets() {
  if (!config.isProd) return;
  const problems = [];
  const warnings = [];
  if (config.jwt.accessSecret.includes('dev-only')) problems.push('JWT_ACCESS_SECRET is still the development default');
  if (config.jwt.refreshSecret.includes('dev-only')) problems.push('JWT_REFRESH_SECRET is still the development default');
  if (config.delivery.exposeDevCodes) problems.push('EXPOSE_DEV_CODES must be false in production');
  if (config.delivery.provider === 'NONE' || config.delivery.provider === 'OUTBOX') {
    // Deliberately not a hard failure when explicitly acknowledged. Neither SMTP nor
    // Twilio is implemented in this build (services/delivery.js throws for both), so
    // demanding a real provider here would make production unbootable — and login
    // does not require verification; only password reset and account verification
    // are affected. The operator must opt in knowingly rather than by omission.
    const msg =
      'DELIVERY_PROVIDER is not configured — password reset and account verification codes are recorded to message_outbox but never delivered';
    if (config.delivery.allowGap) warnings.push(`ACKNOWLEDGED: ${msg}`);
    else problems.push(`${msg}. Set DELIVERY_PROVIDER=SMTP|TWILIO, or set ALLOW_DELIVERY_GAP=true to run knowingly without one.`);
  }
  if (config.uploads.driver === 'supabase') {
    if (!config.uploads.supabaseUrl) problems.push('UPLOAD_DRIVER=supabase requires SUPABASE_URL');
    if (!config.uploads.supabaseServiceRoleKey) {
      problems.push('UPLOAD_DRIVER=supabase requires SUPABASE_SERVICE_ROLE_KEY');
    }
  } else if (config.uploads.driver !== 'local') {
    problems.push(`UPLOAD_DRIVER must be "local" or "supabase" (got "${config.uploads.driver}")`);
  }

  // Earth Engine: an operator who asks for real satellite data must not silently
  // get the MOCK generator. If GEE_ENABLED=true but the credentials did not load,
  // that is a hard production failure — the alternative is a map that looks real
  // and is not.
  if (config.gee.enabled) {
    if (config.gee.credentialProblem) {
      problems.push(`GEE_ENABLED=true but the service-account key could not be loaded: ${config.gee.credentialProblem}`);
    } else if (!config.gee.privateKey) {
      problems.push('GEE_ENABLED=true but no service-account key was supplied (set GEE_KEY_FILE or GEE_KEY_JSON)');
    } else if (!config.gee.serviceAccountEmail) {
      problems.push('GEE_ENABLED=true but the service-account email is missing (set GEE_SERVICE_ACCOUNT_EMAIL or use a full key file)');
    } else if (!config.gee.projectId) {
      problems.push('GEE_ENABLED=true but the project id is missing (set GEE_PROJECT_ID or use a full key file)');
    }
  }
  if (problems.length) {
    // eslint-disable-next-line no-console
    console.error('REFUSING TO START IN PRODUCTION:\n - ' + problems.join('\n - '));
    process.exit(1);
  }
  if (warnings.length) {
    // eslint-disable-next-line no-console
    console.warn('[config] production warnings:\n - ' + warnings.join('\n - '));
  }
}
