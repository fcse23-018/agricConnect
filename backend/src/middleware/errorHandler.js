import { ZodError } from 'zod';
import { AppError, ErrorCode } from '../utils/errors.js';
import { config } from '../config/index.js';

/** Validates req[source] against a zod schema and replaces it with the parsed value. */
export function validate(schema, source = 'body') {
  return (req, _res, next) => {
    const result = schema.safeParse(req[source]);
    if (!result.success) {
      const details = result.error.issues.map((i) => ({
        field: i.path.join('.') || source,
        message: i.message,
      }));
      return next(new AppError(ErrorCode.VALIDATION_ERROR, 'Please check your entries and try again.', { details }));
    }
    req[source] = result.data;
    next();
  };
}

/** Parse ?page & ?pageSize with sane bounds. */
export function pagination(req, _res, next) {
  const page = Math.max(1, Number.parseInt(req.query.page, 10) || 1);
  const pageSize = Math.min(100, Math.max(1, Number.parseInt(req.query.pageSize, 10) || 20));
  req.pagination = { page, pageSize, offset: (page - 1) * pageSize };
  next();
}

/** 404 for unknown API routes. */
export function notFoundHandler(req, _res, next) {
  next(new AppError(ErrorCode.NOT_FOUND, `No route matches ${req.method} ${req.path}`));
}

/**
 * Centralised error handler. Logs the real cause server-side, but only ever
 * returns safe, generic text to the client — no stack traces, no SQL, no
 * account-existence leaks.
 */
// eslint-disable-next-line no-unused-vars
export function errorHandler(err, req, res, _next) {
  const requestId = req.id;

  if (err instanceof ZodError) {
    return res.status(400).json({
      error: {
        code: ErrorCode.VALIDATION_ERROR,
        message: 'Please check your entries and try again.',
        details: err.issues.map((i) => ({ field: i.path.join('.'), message: i.message })),
      },
      requestId,
    });
  }

  const isApp = err instanceof AppError;
  const status = isApp ? err.status : 500;
  const code = isApp ? err.code : ErrorCode.INTERNAL;
  const message = isApp && err.expose ? err.message : 'Something went wrong on our side. Please try again.';

  const logPayload = {
    requestId,
    status,
    code,
    method: req.method,
    path: req.originalUrl,
    userId: req.user?.id,
    msg: err.message,
  };
  if (status >= 500) {
    // eslint-disable-next-line no-console
    console.error('[error]', logPayload, config.isProd ? '' : err.stack);
  } else {
    // eslint-disable-next-line no-console
    console.warn('[warn]', logPayload);
  }

  const body = { error: { code, message }, requestId };
  if (isApp && err.details) body.error.details = err.details;
  res.status(status).json(body);
}

export default { validate, pagination, notFoundHandler, errorHandler };
