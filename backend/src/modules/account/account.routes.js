import { Router } from 'express';
import { z } from 'zod';

import { one, many, withTransaction } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { invalidCredentials, unauthorized, notFound, conflict } from '../../utils/errors.js';
import { verifyPassword } from '../../utils/password.js';
import { iso, dateOnly } from '../../utils/serialize.js';
import { audit } from '../../services/audit.js';
import { findUserById } from '../auth/auth.service.js';
import { createDeletionRequest } from './anonymize.js';

/**
 * Account deletion endpoints for the signed-in user.
 *
 * Mounted at the API root by modules/index.js (see the comment there), so the
 * paths below include the full `/users/me/deletion-request` prefix. The public
 * (unauthenticated) variant lives in modules/public/public.routes.js.
 *
 * The request schema is defined inline because the file deliverable list does
 * not include an account.schemas.js; it is intentionally small.
 */
const router = Router();

const deletionRequestSchema = z.object({
  password: z.string({ required_error: 'Password is required.' }).min(1, 'Password is required.'),
  reason: z.string().trim().max(500, 'Reason must be at most 500 characters.').optional(),
});

async function retentionFor(userId) {
  const rows = await many(
    // `retain_until::text` avoids a local-midnight Date being shifted a day by UTC conversion.
    `SELECT data_category, justification, retain_until::text AS retain_until
       FROM retention_ledger WHERE subject_user = $1 ORDER BY data_category`,
    [userId],
  );
  return rows.map((row) => ({
    dataCategory: row.data_category,
    justification: row.justification,
    retainUntil: dateOnly(row.retain_until),
  }));
}

function toDeletionRequest(row, retention = []) {
  if (!row) return null;
  return {
    requestId: row.id,
    status: row.status,
    reason: row.reason ?? null,
    requestedAt: iso(row.requested_at),
    effectiveAt: iso(row.effective_at),
    anonymisedAt: iso(row.anonymised_at),
    completedAt: iso(row.completed_at),
    cancelledAt: iso(row.cancelled_at),
    retention,
  };
}

// This router is mounted at the API root (see modules/index.js), so authentication
// is applied per-route. A router-level `use(authenticate)` would intercept every
// request bound for routers mounted after this one.
router.post(
  '/users/me/deletion-request',
  authenticate,
  validate(deletionRequestSchema, 'body'),
  asyncHandler(async (req, res) => {
    const user = await findUserById(req.user.id);
    if (!user || user.deleted_at) throw unauthorized('Your account is no longer available.');

    // Deletion is destructive: re-authentication with the password is required.
    const ok = await verifyPassword(req.body.password, user.password_hash);
    if (!ok) {
      await audit(req, 'ACCOUNT_DELETION_REAUTH_FAILED', { entityType: 'user', entityId: user.id });
      throw invalidCredentials();
    }

    const { request, retention, created } = await createDeletionRequest({
      userId: user.id,
      reason: req.body.reason,
    });

    await audit(req, 'ACCOUNT_DELETION_REQUESTED', {
      entityType: 'user',
      entityId: user.id,
      metadata: { reason: req.body.reason ?? null, alreadyPending: !created },
    });

    // 202 when a new grace period starts; 200 when returning an existing request.
    res.status(created ? 202 : 200).json({
      requestId: request.id,
      status: request.status,
      requestedAt: iso(request.requested_at),
      effectiveAt: iso(request.effective_at),
      retention,
    });
  }),
);

router.get(
  '/users/me/deletion-request',
  authenticate,
  asyncHandler(async (req, res) => {
    const request = await one(
      `SELECT * FROM account_deletion_requests
        WHERE user_id = $1 ORDER BY requested_at DESC LIMIT 1`,
      [req.user.id],
    );
    if (!request) return res.json({ request: null });
    res.json({ request: toDeletionRequest(request, await retentionFor(req.user.id)) });
  }),
);

router.post(
  '/users/me/deletion-request/cancel',
  authenticate,
  asyncHandler(async (req, res) => {
    const request = await one(
      `SELECT * FROM account_deletion_requests
        WHERE user_id = $1 AND status = 'PENDING'
        ORDER BY requested_at DESC LIMIT 1`,
      [req.user.id],
    );
    if (!request) {
      const anyRequest = await one(
        `SELECT 1 FROM account_deletion_requests WHERE user_id = $1 LIMIT 1`,
        [req.user.id],
      );
      if (anyRequest) throw conflict('This deletion request can no longer be cancelled.');
      throw notFound('You do not have an active deletion request.');
    }
    if (new Date(request.effective_at).getTime() <= Date.now()) {
      throw conflict('The grace period has ended; this request can no longer be cancelled.');
    }

    const user = await findUserById(req.user.id);
    // Restore the status the account held before the request.
    const restoredStatus = user?.verified_at ? 'VERIFIED' : 'UNVERIFIED';

    await withTransaction(async (client) => {
      await client.query(
        `UPDATE account_deletion_requests
            SET status = 'CANCELLED', cancelled_at = now()
          WHERE id = $1`,
        [request.id],
      );
      await client.query(`UPDATE users SET account_status = $2 WHERE id = $1`, [req.user.id, restoredStatus]);
    });

    const updated = await one(`SELECT * FROM account_deletion_requests WHERE id = $1`, [request.id]);
    await audit(req, 'ACCOUNT_DELETION_CANCELLED', { entityType: 'user', entityId: req.user.id });
    res.json({ request: toDeletionRequest(updated, await retentionFor(req.user.id)) });
  }),
);

export default router;
