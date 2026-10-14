import { one } from '../db/index.js';
import { sha256 } from '../utils/crypto.js';

/**
 * Idempotency guard for POST endpoints.
 *
 * Why this matters for the brief: "Never create duplicate accounts because a
 * user taps the registration button twice or retries after a network failure."
 *
 * Behaviour:
 *  - No `Idempotency-Key` header  -> pass through (endpoint-level dedupe still
 *    applies, e.g. a unique phone number will still be rejected with 409).
 *  - Key seen before              -> replay the stored response verbatim.
 *  - Key new                      -> capture the response and store it.
 *
 * `scope` distinguishes endpoints so the same key on two endpoints is harmless.
 */
export function idempotency(scope) {
  return async (req, res, next) => {
    const key = req.headers['idempotency-key'];
    if (!key || typeof key !== 'string' || key.length > 200) return next();

    const composite = `${scope}:${key}`;
    const requestHash = sha256(JSON.stringify(req.body ?? {}));

    try {
      const existing = await one(
        `SELECT response_status, response_body, request_hash
           FROM idempotency_keys WHERE key = $1`,
        [composite],
      );
      if (existing) {
        if (existing.request_hash && existing.request_hash !== requestHash) {
          // Same key, different body: refuse rather than silently replaying.
          return res.status(409).json({
            error: {
              code: 'CONFLICT',
              message: 'This request key was already used with different details.',
            },
            requestId: req.id,
          });
        }
        res.set('Idempotency-Replayed', 'true');
        return res.status(existing.response_status || 200).json(existing.response_body);
      }
    } catch {
      // If the idempotency store is unavailable, fail open (do not block the user).
      return next();
    }

    const originalJson = res.json.bind(res);
    res.json = (body) => {
      if (res.statusCode < 500) {
        one(
          `INSERT INTO idempotency_keys (key, user_id, endpoint, request_hash, response_status, response_body)
           VALUES ($1,$2,$3,$4,$5,$6)
           ON CONFLICT (key) DO NOTHING`,
          [composite, req.user?.id ?? null, scope, requestHash, res.statusCode, body],
        ).catch(() => {});
      }
      return originalJson(body);
    };

    next();
  };
}

export default idempotency;
