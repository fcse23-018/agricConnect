/**
 * Canonical application error. The `code` is safe to expose to clients; the
 * `message` is a safe, human-readable string. Internal causes are never sent.
 */
export const ErrorCode = {
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  TOKEN_EXPIRED: 'TOKEN_EXPIRED',
  ACCOUNT_UNVERIFIED: 'ACCOUNT_UNVERIFIED',
  ACCOUNT_SUSPENDED: 'ACCOUNT_SUSPENDED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  CONFLICT: 'CONFLICT',
  DUPLICATE_ACCOUNT: 'DUPLICATE_ACCOUNT',
  RATE_LIMITED: 'RATE_LIMITED',
  UNSUPPORTED_MEDIA: 'UNSUPPORTED_MEDIA',
  INTERNAL: 'INTERNAL',
};

const STATUS = {
  [ErrorCode.VALIDATION_ERROR]: 400,
  [ErrorCode.UNAUTHENTICATED]: 401,
  [ErrorCode.INVALID_CREDENTIALS]: 401,
  [ErrorCode.TOKEN_EXPIRED]: 401,
  [ErrorCode.ACCOUNT_UNVERIFIED]: 403,
  [ErrorCode.ACCOUNT_SUSPENDED]: 403,
  [ErrorCode.FORBIDDEN]: 403,
  [ErrorCode.NOT_FOUND]: 404,
  [ErrorCode.CONFLICT]: 409,
  [ErrorCode.DUPLICATE_ACCOUNT]: 409,
  [ErrorCode.RATE_LIMITED]: 429,
  [ErrorCode.UNSUPPORTED_MEDIA]: 415,
  [ErrorCode.INTERNAL]: 500,
};

export class AppError extends Error {
  constructor(code, message, { status, details, cause } = {}) {
    super(message);
    this.name = 'AppError';
    this.code = code;
    this.status = status ?? STATUS[code] ?? 500;
    this.details = details;
    this.cause = cause;
    this.expose = this.status < 500;
  }
}

export const badRequest = (message, details) => new AppError(ErrorCode.VALIDATION_ERROR, message, { details });
export const unauthorized = (message = 'Authentication required.') => new AppError(ErrorCode.UNAUTHENTICATED, message);
export const invalidCredentials = () => new AppError(ErrorCode.INVALID_CREDENTIALS, 'Invalid credentials. Please check and try again.');
export const forbidden = (message = 'You do not have permission to perform this action.') => new AppError(ErrorCode.FORBIDDEN, message);
export const notFound = (message = 'The requested resource was not found.') => new AppError(ErrorCode.NOT_FOUND, message);
export const conflict = (message = 'The record was changed by someone else.') => new AppError(ErrorCode.CONFLICT, message);
export const duplicateAccount = (message = 'An account with those details already exists.') => new AppError(ErrorCode.DUPLICATE_ACCOUNT, message);
export const internal = (message = 'Something went wrong. Please try again.', cause) => new AppError(ErrorCode.INTERNAL, message, { cause });

export default { AppError, ErrorCode };
