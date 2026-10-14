import { Router } from 'express';
import { z } from 'zod';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { publicReadLimiter } from '../../middleware/rateLimit.js';
import { audit } from '../../services/audit.js';
import { findUserByIdentifier } from '../auth/auth.service.js';
import { createDeletionRequest } from '../account/anonymize.js';

/**
 * Unauthenticated endpoints that app stores require (public account-deletion
 * page). The response is *always* the same generic 202 so the endpoint cannot
 * be used to discover which phone numbers or emails are registered.
 *
 * The schema is inline because the deliverable list does not include a
 * public.schemas.js.
 */
const router = Router();

const publicDeletionSchema = z.object({
  identifier: z
    .string({ required_error: 'Phone number or email is required.' })
    .trim()
    .min(1, 'Phone number or email is required.')
    .max(254, 'That identifier is too long.'),
  reason: z.string().trim().max(500, 'Reason must be at most 500 characters.').optional(),
});

router.post(
  '/account-deletion',
  publicReadLimiter,
  validate(publicDeletionSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { identifier, reason } = req.body;
    const message = 'If an account matches those details, the deletion request has been recorded.';

    const user = await findUserByIdentifier(identifier);
    if (user) {
      // Same lifecycle as the authenticated flow: PENDING + grace period +
      // retention ledger + immediate session revocation. It is idempotent, so a
      // repeat submission while a request is already pending is harmless.
      await createDeletionRequest({ userId: user.id, reason });
      await audit(req, 'PUBLIC_DELETION_REQUESTED', { entityType: 'user', entityId: user.id });
    }

    res.status(202).json({ message });
  }),
);

export default router;
