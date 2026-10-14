/** Wrap an async express handler so rejected promises reach the error middleware. */
export function asyncHandler(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}

/** Wrap an async express middleware. */
export function asyncMiddleware(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}

export default asyncHandler;
