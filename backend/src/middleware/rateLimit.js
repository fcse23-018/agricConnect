import rateLimit from 'express-rate-limit';
import { config } from '../config/index.js';
import { ErrorCode } from '../utils/errors.js';

const message = {
  error: {
    code: ErrorCode.RATE_LIMITED,
    message: 'Too many requests. Please wait a moment and try again.',
  },
};

/** Limits are disabled under test so suites can exercise flows freely. */
const skip = () => config.isTest;

export const authLimiter = rateLimit({
  windowMs: config.rateLimit.authWindowMs,
  max: config.rateLimit.authMax,
  standardHeaders: true,
  legacyHeaders: false,
  skip,
  message,
});

export const syncLimiter = rateLimit({
  windowMs: 15 * 60_000,
  max: config.rateLimit.syncMax,
  standardHeaders: true,
  legacyHeaders: false,
  skip,
  keyGenerator: (req) => req.user?.id || req.ip,
  message,
});

export const publicReadLimiter = rateLimit({
  windowMs: 15 * 60_000,
  max: config.rateLimit.publicMax,
  standardHeaders: true,
  legacyHeaders: false,
  skip,
  message,
});

export default { authLimiter, syncLimiter, publicReadLimiter };
