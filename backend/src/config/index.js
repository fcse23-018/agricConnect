import 'dotenv/config';

function bool(v, def = false) {
  if (v === undefined || v === '') return def;
  return ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase());
}
function int(v, def) {
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : def;
}

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
    serviceAccountEmail: process.env.GEE_SERVICE_ACCOUNT_EMAIL || '',
    privateKey: (process.env.GEE_PRIVATE_KEY || '').replace(/\\n/g, '\n'),
    projectId: process.env.GEE_PROJECT_ID || '',
    cloudCoverFailThreshold: int(process.env.NDVI_CLOUD_COVER_FAIL_THRESHOLD, 80),
  },

  uploads: {
    dir: process.env.UPLOAD_DIR || './uploads',
    maxBytes: int(process.env.MAX_UPLOAD_BYTES, 2 * 1024 * 1024),
  },

  rateLimit: {
    authMax: int(process.env.RATE_LIMIT_AUTH_MAX, 10),
    authWindowMs: int(process.env.RATE_LIMIT_AUTH_WINDOW_MINUTES, 15) * 60_000,
    syncMax: int(process.env.RATE_LIMIT_SYNC_MAX, 120),
    publicMax: int(process.env.RATE_LIMIT_PUBLIC_MAX, 300),
  },

  logLevel: process.env.LOG_LEVEL || 'info',
};

export function assertProdSecrets() {
  if (!config.isProd) return;
  const problems = [];
  if (config.jwt.accessSecret.includes('dev-only')) problems.push('JWT_ACCESS_SECRET is still the development default');
  if (config.jwt.refreshSecret.includes('dev-only')) problems.push('JWT_REFRESH_SECRET is still the development default');
  if (config.delivery.exposeDevCodes) problems.push('EXPOSE_DEV_CODES must be false in production');
  if (config.delivery.provider === 'NONE' || config.delivery.provider === 'OUTBOX') {
    problems.push('DELIVERY_PROVIDER is not configured — password recovery cannot reach users in production');
  }
  if (problems.length) {
    // eslint-disable-next-line no-console
    console.error('REFUSING TO START IN PRODUCTION:\n - ' + problems.join('\n - '));
    process.exit(1);
  }
}
