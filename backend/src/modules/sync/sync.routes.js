import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { syncLimiter } from '../../middleware/rateLimit.js';
import { idempotency } from '../../middleware/idempotency.js';
import { pullQuerySchema, pushSchema } from './sync.schemas.js';
import { pullChanges, pushItems } from './sync.service.js';

const router = Router();

router.use(authenticate);

/**
 * POST /sync/push
 * Rate-limited per user; also accepts an `Idempotency-Key` (contract §0). The
 * primary idempotency guarantee is per-item (`queueId`), which works even when
 * the client cannot send a header. Always returns HTTP 200: the batch was
 * accepted regardless of individual item outcomes.
 */
router.post(
  '/push',
  syncLimiter,
  validate(pushSchema, 'body'),
  idempotency('sync.push'),
  asyncHandler(async (req, res) => {
    const { items, deviceId } = req.body;
    const result = await pushItems({
      userId: req.user.id,
      role: req.user.role,
      deviceId: deviceId ?? null,
      items,
    });
    res.status(200).json(result);
  }),
);

/**
 * GET /sync/pull?since=ISO8601
 * Returns server-authoritative changes after `since` for the caller's scope.
 */
router.get(
  '/pull',
  validate(pullQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const { since } = req.query;
    const result = await pullChanges({
      userId: req.user.id,
      role: req.user.role,
      since: since ?? null,
    });
    res.json(result);
  }),
);

export default router;
