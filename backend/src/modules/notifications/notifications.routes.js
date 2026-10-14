import { Router } from 'express';
import { z } from 'zod';

import { many, one, query } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { notFound } from '../../utils/errors.js';
import { paginated, toNotification } from '../../utils/serialize.js';
import { pagination, validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';

const router = Router();

router.use(authenticate);

// Query keys are validated/replaced by `validate`; `unreadOnly` arrives as a string.
const listQuerySchema = z
  .object({
    unreadOnly: z.enum(['true', 'false']).optional(),
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
  })
  .passthrough();

const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid notification identifier is required.' }),
});

const registerDeviceSchema = z
  .object({
    token: z.string().trim().min(1).max(4096),
    platform: z.string().trim().min(1).max(40).default('android'),
  })
  .strict();

/** GET /notifications — the caller's own feed, newest first. */
router.get(
  '/',
  validate(listQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { unreadOnly } = req.query;
    const { page, pageSize, offset } = req.pagination;

    const where = ['user_id = $1'];
    const params = [req.user.id];
    if (unreadOnly === 'true') where.push('is_read = false');
    const whereSql = where.join(' AND ');

    const totalRow = await one(
      `SELECT count(*)::int AS total FROM notifications WHERE ${whereSql}`,
      params,
    );

    params.push(pageSize, offset);
    const rows = await many(
      `SELECT * FROM notifications
        WHERE ${whereSql}
        ORDER BY created_at DESC
        LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params,
    );

    res.json(paginated(rows.map(toNotification), { page, pageSize, total: totalRow.total }));
  }),
);

/** PATCH /notifications/:id/read — mark one owned notification as read. */
router.patch(
  '/:id/read',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `UPDATE notifications
          SET is_read = true
        WHERE id = $1 AND user_id = $2
        RETURNING *`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('The requested notification was not found.');

    res.json({ notification: toNotification(row) });
  }),
);

/** POST /notifications/read-all — mark every unread notification as read. */
router.post(
  '/read-all',
  asyncHandler(async (req, res) => {
    const result = await query(
      `UPDATE notifications SET is_read = true WHERE user_id = $1 AND is_read = false`,
      [req.user.id],
    );
    res.json({ updated: result.rowCount });
  }),
);

/**
 * POST /notifications/devices — register a push token.
 * Idempotent: re-registering the same token for the same user is a no-op.
 */
router.post(
  '/devices',
  validate(registerDeviceSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { token, platform } = req.body;
    await query(
      `INSERT INTO device_tokens (user_id, token, platform)
       VALUES ($1,$2,$3)
       ON CONFLICT (user_id, token) DO NOTHING`,
      [req.user.id, token, platform],
    );
    res.status(204).end();
  }),
);

export default router;
