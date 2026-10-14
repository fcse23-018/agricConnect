import { z } from 'zod';

import { phoneNumberSchema, optionalEmailSchema, passwordSchema } from '../auth/auth.schemas.js';

/**
 * Profile-update schema.
 *
 * `.strict()` is deliberate: sending `role` (or any other privilege field) is a
 * validation error rather than a silently-ignored no-op, which makes the
 * "clients cannot self-elevate" rule explicit and test-visible.
 */
export const updateProfileSchema = z
  .object({
    fullName: z
      .string()
      .trim()
      .min(2, 'Full name must be at least 2 characters.')
      .max(120, 'Full name must be at most 120 characters.')
      .optional(),
    email: optionalEmailSchema,
  })
  .strict();

export const changePasswordSchema = z.object({
  currentPassword: z
    .string({ required_error: 'Current password is required.' })
    .min(1, 'Current password is required.'),
  newPassword: passwordSchema,
  // Optional: the token of the session making the change, kept alive so the
  // user is not logged out on their own device.
  keepRefreshToken: z.string().min(1).max(500).optional(),
});

export const notificationPreferencesSchema = z
  .object({
    orders: z.boolean().optional(),
    sync: z.boolean().optional(),
    satellite: z.boolean().optional(),
    marketplace: z.boolean().optional(),
  })
  .strict();

export const employerLinkSchema = z.object({
  employerPhone: phoneNumberSchema,
});

export default {
  updateProfileSchema,
  changePasswordSchema,
  notificationPreferencesSchema,
  employerLinkSchema,
};
