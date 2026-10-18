import { Router } from 'express';

import { config } from '../../config/index.js';
import { one, many, withTransaction } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { authorizeRoles, ROLES } from '../../middleware/roles.js';
import { singleImage, publicUrl } from '../../services/uploads.js';
import {
  AppError,
  badRequest,
  unauthorized,
  invalidCredentials,
  duplicateAccount,
  notFound,
} from '../../utils/errors.js';
import { hashPassword, verifyPassword, validatePasswordStrength } from '../../utils/password.js';
import { sha256, maskIdentifier } from '../../utils/crypto.js';
import {
  toPlot,
  toHarvest,
  toLivestock,
  toSale,
  toExpense,
  toListing,
  toOrder,
  toNotification,
  toNdvi,
} from '../../utils/serialize.js';
import { audit } from '../../services/audit.js';
import { sendVerificationCode } from '../../services/delivery.js';
import {
  serializeUser,
  findUserById,
  createVerificationCode,
} from '../auth/auth.service.js';
import {
  updateProfileSchema,
  changePasswordSchema,
  notificationPreferencesSchema,
  employerLinkSchema,
} from './users.schemas.js';

// Mounted at /users by modules/index.js — profile routes use the /me prefix.
const router = Router();

router.use(authenticate);

/** Load the caller's own row (routes are always self-scoped). */
async function loadSelf(userId) {
  const user = await findUserById(userId);
  if (!user) throw unauthorized('Your account is no longer available. Please sign in again.');
  return user;
}

router.get(
  '/me',
  asyncHandler(async (req, res) => {
    res.json({ user: serializeUser(await loadSelf(req.user.id)) });
  }),
);

router.patch(
  '/me',
  validate(updateProfileSchema, 'body'),
  asyncHandler(async (req, res) => {
    const current = await loadSelf(req.user.id);
    const { fullName, email } = req.body;

    const wantsEmailChange =
      email !== undefined && email !== null && email.toLowerCase() !== String(current.email ?? '').toLowerCase();

    let updated;
    let devVerificationCode;

    if (wantsEmailChange) {
      const clash = await one(
        `SELECT id FROM users WHERE lower(email) = lower($1) AND deleted_at IS NULL AND id <> $2`,
        [email, req.user.id],
      );
      if (clash) throw duplicateAccount('That email address is already in use by another account.');

      // A new address must be proven: downgrade until the emailed code confirms.
      updated = await one(
        `UPDATE users
            SET full_name = COALESCE($2, full_name),
                email = $3,
                account_status = 'UNVERIFIED',
                verified_at = NULL
          WHERE id = $1
          RETURNING *`,
        [req.user.id, fullName ?? null, email],
      );

      const ttlMinutes = config.tokens.verificationCodeTtlMinutes;
      const code = await createVerificationCode(updated.id, 'EMAIL_VERIFICATION', {
        ttlMinutes,
        destination: maskIdentifier(email),
      });
      await sendVerificationCode({ to: email, code, expiresMinutes: ttlMinutes });
      if (config.delivery.exposeDevCodes) devVerificationCode = code;
    } else if (fullName !== undefined) {
      updated = await one(
        `UPDATE users SET full_name = $2 WHERE id = $1 RETURNING *`,
        [req.user.id, fullName],
      );
    } else {
      // Nothing to change — return the current profile rather than issuing a no-op UPDATE.
      return res.json({ user: serializeUser(current) });
    }

    await audit(req, 'PROFILE_UPDATED', { entityType: 'user', entityId: req.user.id });
    res.json({
      user: serializeUser(updated),
      ...(devVerificationCode ? { devVerificationCode } : {}),
    });
  }),
);

router.post(
  '/me/password',
  validate(changePasswordSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { currentPassword, newPassword, keepRefreshToken } = req.body;
    const current = await loadSelf(req.user.id);

    const ok = await verifyPassword(currentPassword, current.password_hash);
    if (!ok) {
      await audit(req, 'PASSWORD_CHANGE_FAILED', { entityType: 'user', entityId: req.user.id });
      throw invalidCredentials();
    }

    const policy = validatePasswordStrength(newPassword);
    if (!policy.ok) {
      throw badRequest(
        'Please choose a stronger password.',
        policy.problems.map((message) => ({ field: 'newPassword', message })),
      );
    }

    const passwordHash = await hashPassword(newPassword);
    const keepHash = keepRefreshToken ? sha256(keepRefreshToken) : null;
    await withTransaction(async (client) => {
      await client.query(
        `UPDATE users SET password_hash = $2, failed_login_count = 0, locked_until = NULL WHERE id = $1`,
        [req.user.id, passwordHash],
      );
      if (keepHash) {
        // Revoke every other session, keep the caller's own device signed in.
        await client.query(
          `UPDATE refresh_tokens SET revoked_at = now()
            WHERE user_id = $1 AND revoked_at IS NULL AND token_hash <> $2`,
          [req.user.id, keepHash],
        );
      } else {
        await client.query(
          `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
          [req.user.id],
        );
      }
    });

    await audit(req, 'PASSWORD_CHANGED', { entityType: 'user', entityId: req.user.id });
    res.json({ message: 'Your password has been changed.' });
  }),
);

router.put(
  '/me/notification-preferences',
  validate(notificationPreferencesSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { orders, sync, satellite, marketplace } = req.body;
    const patch = {};
    if (orders !== undefined) patch.orders = orders;
    if (sync !== undefined) patch.sync = sync;
    if (satellite !== undefined) patch.satellite = satellite;
    if (marketplace !== undefined) patch.marketplace = marketplace;

    if (Object.keys(patch).length === 0) {
      return res.json({ user: serializeUser(await loadSelf(req.user.id)) });
    }

    const updated = await one(
      `UPDATE users
          SET notification_preferences = notification_preferences || $2::jsonb
        WHERE id = $1
        RETURNING *`,
      [req.user.id, JSON.stringify(patch)],
    );
    await audit(req, 'NOTIFICATION_PREFERENCES_UPDATED', { entityType: 'user', entityId: req.user.id });
    res.json({ user: serializeUser(updated) });
  }),
);

// --- Avatar upload ---------------------------------------------------------
//
// Reuses the shared image pipeline in services/uploads.js (jpeg/png/webp, size
// cap, UUID filename) — only the multipart field name and stored prefix are
// avatar-specific. The stored path is what lands in users.avatar_url.

const singleAvatar = singleImage('avatar', 'avatar-');

router.post(
  '/me/avatar',
  singleAvatar,
  asyncHandler(async (req, res) => {
    if (!req.file) throw badRequest('An avatar image is required (field name "avatar").');
    const updated = await one(
      `UPDATE users SET avatar_url = $2 WHERE id = $1 RETURNING *`,
      [req.user.id, publicUrl(req.file)],
    );
    await audit(req, 'AVATAR_UPDATED', { entityType: 'user', entityId: req.user.id });
    res.json({ user: serializeUser(updated) });
  }),
);

// --- Personal-data export --------------------------------------------------

router.get(
  '/me/export',
  asyncHandler(async (req, res) => {
    const userId = req.user.id;
    const [user, plots, harvests, livestock, sales, expenses, listings, buyerOrders, farmerOrders, notifications, ndvi] =
      await Promise.all([
        loadSelf(userId),
        many(`SELECT * FROM farm_plots WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY created_at`, [userId]),
        many(`SELECT * FROM harvest_records WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY harvest_date DESC`, [userId]),
        many(`SELECT * FROM livestock_counts WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY record_date DESC`, [userId]),
        many(`SELECT * FROM sale_transactions WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY sale_date DESC`, [userId]),
        many(`SELECT * FROM expenses WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY expense_date DESC`, [userId]),
        many(`SELECT * FROM produce_listings WHERE farmer_id = $1 AND deleted_at IS NULL ORDER BY created_at DESC`, [userId]),
        many(
          `SELECT o.*, l.produce FROM orders o
             JOIN produce_listings l ON l.id = o.listing_id
            WHERE o.buyer_id = $1 ORDER BY o.created_at DESC`,
          [userId],
        ),
        many(
          `SELECT o.*, l.produce FROM orders o
             JOIN produce_listings l ON l.id = o.listing_id
            WHERE o.farmer_id = $1 ORDER BY o.created_at DESC`,
          [userId],
        ),
        many(`SELECT * FROM notifications WHERE user_id = $1 ORDER BY created_at DESC`, [userId]),
        many(`SELECT * FROM satellite_ndvi WHERE farmer_id = $1 ORDER BY created_at DESC`, [userId]),
      ]);

    // Only serialisable, non-secret fields are exported — no password_hash, no token hashes.
    const payload = {
      exportedAt: new Date().toISOString(),
      user: serializeUser(user),
      farmPlots: plots.map(toPlot),
      harvests: harvests.map(toHarvest),
      livestock: livestock.map(toLivestock),
      sales: sales.map(toSale),
      expenses: expenses.map(toExpense),
      listings: listings.map(toListing),
      ordersAsBuyer: buyerOrders.map(toOrder),
      ordersAsFarmer: farmerOrders.map(toOrder),
      notifications: notifications.map(toNotification),
      satelliteNdvi: ndvi.map(toNdvi),
    };

    res.setHeader('Content-Disposition', 'attachment; filename="agriconnect-export.json"');
    res.json(payload);
  }),
);

// --- Employee → employer link (migration 005 gap fix) ----------------------

router.post(
  '/me/employer-link',
  authorizeRoles(ROLES.FARM_EMPLOYEE),
  validate(employerLinkSchema, 'body'),
  asyncHandler(async (req, res) => {
    const farmer = await one(
      `SELECT id FROM users WHERE phone_number = $1 AND role = 'FARMER' AND deleted_at IS NULL`,
      [req.body.employerPhone],
    );
    if (!farmer) throw notFound('No farmer account matches that phone number.');

    const updated = await one(
      `UPDATE users SET employer_farmer_id = $2 WHERE id = $1 RETURNING *`,
      [req.user.id, farmer.id],
    );
    await audit(req, 'EMPLOYER_LINKED', {
      entityType: 'user',
      entityId: req.user.id,
      metadata: { employerFarmerId: farmer.id },
    });
    res.json({ user: serializeUser(updated) });
  }),
);

export default router;
