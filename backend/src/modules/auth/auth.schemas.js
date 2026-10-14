import { z } from 'zod';

import { ROLES } from '../../middleware/roles.js';
import { validatePasswordStrength } from '../../utils/password.js';

/**
 * Request schemas for the authentication module.
 *
 * Security notes that are easy to get wrong and therefore live here:
 *  - `role` is a closed enum. Any privileged field a client invents
 *    (`accountStatus`, `isAdmin`, `employerFarmerId`, …) is *stripped* by zod's
 *    default "strip" behaviour — the client can never widen its own privileges.
 *  - Phone numbers are normalised (all whitespace removed) before the E.164
 *    check so "+267 71 234 567" cannot dodge validation.
 */

/** E.164 international phone, e.g. +26771234567. Whitespace is removed first. */
export const phoneNumberSchema = z
  .string({
    required_error: 'Phone number is required.',
    invalid_type_error: 'Phone number is required.',
  })
  .transform((value) => value.replace(/\s+/g, ''))
  .pipe(
    z
      .string()
      .regex(/^\+[1-9][0-9]{7,14}$/, 'Enter a valid phone number in international format, e.g. +26771234567.'),
  );

/**
 * Optional email. Empty strings and null are treated as "not supplied" so a
 * mobile client that always sends the field does not trip validation.
 */
export const optionalEmailSchema = z.preprocess(
  (value) => (value === null || (typeof value === 'string' && value.trim() === '') ? undefined : value),
  z
    .string()
    .trim()
    .toLowerCase()
    .max(254, 'Email address is too long.')
    .email('Enter a valid email address.')
    .optional(),
);

/** Password policy is duplicated from the middleware because schemas can be reused standalone. */
export const passwordSchema = z.string().superRefine((value, ctx) => {
  const { ok, problems } = validatePasswordStrength(value);
  if (!ok) {
    for (const message of problems) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message });
    }
  }
});

/** Six-digit numeric verification / recovery code. */
export const numericCodeSchema = z
  .string({ required_error: 'The 6-digit code is required.' })
  .trim()
  .regex(/^[0-9]{6}$/, 'Enter the 6-digit code.');

// A consent flag must be the literal `true`; `false`, missing and truthy strings all fail.
const acceptLiteral = (message) => z.literal(true, { errorMap: () => ({ message }) });

export const registerSchema = z
  .object({
    fullName: z
      .string({ required_error: 'Full name is required.' })
      .trim()
      .min(2, 'Full name must be at least 2 characters.')
      .max(120, 'Full name must be at most 120 characters.'),
    phoneNumber: phoneNumberSchema,
    email: optionalEmailSchema,
    password: passwordSchema,
    confirmPassword: z.string().optional(),
    role: z.enum(Object.values(ROLES), {
      errorMap: () => ({ message: 'Role must be one of FARMER, FARM_EMPLOYEE or BUYER.' }),
    }),
    acceptTerms: acceptLiteral('You must accept the Terms of Service to create an account.'),
    acceptPrivacy: acceptLiteral('You must accept the Privacy Policy to create an account.'),
  })
  .superRefine((value, ctx) => {
    if (value.confirmPassword !== undefined && value.confirmPassword !== value.password) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['confirmPassword'], message: 'Passwords do not match.' });
    }
  });

export const loginSchema = z.object({
  identifier: z
    .string({ required_error: 'Phone number or email is required.' })
    .trim()
    .min(1, 'Phone number or email is required.')
    .max(254, 'That identifier is too long.'),
  password: z.string({ required_error: 'Password is required.' }).min(1, 'Password is required.'),
});

export const refreshSchema = z.object({
  refreshToken: z.string({ required_error: 'Refresh token is required.' }).min(1, 'Refresh token is required.').max(500),
});

/**
 * Logout accepts an empty body; `.default({})` makes an absent body valid so
 * the endpoint never 400s just because the client sent no payload.
 */
export const logoutSchema = z
  .object({
    refreshToken: z.string().min(1).max(500).optional(),
  })
  .default({});

export const identifierSchema = z.object({
  identifier: z
    .string({ required_error: 'Phone number or email is required.' })
    .trim()
    .min(1, 'Phone number or email is required.')
    .max(254, 'That identifier is too long.'),
});

export const passwordForgotSchema = identifierSchema;

export const passwordVerifySchema = z.object({
  identifier: z
    .string({ required_error: 'Phone number or email is required.' })
    .trim()
    .min(1, 'Phone number or email is required.')
    .max(254, 'That identifier is too long.'),
  code: numericCodeSchema,
});

export const passwordResetSchema = z.object({
  resetToken: z
    .string({ required_error: 'Reset token is required.' })
    .min(1, 'Reset token is required.')
    .max(500),
  newPassword: passwordSchema,
});

export const verificationRequestSchema = identifierSchema;

export const verificationConfirmSchema = passwordVerifySchema;

export default {
  phoneNumberSchema,
  optionalEmailSchema,
  passwordSchema,
  numericCodeSchema,
  registerSchema,
  loginSchema,
  refreshSchema,
  logoutSchema,
  identifierSchema,
  passwordForgotSchema,
  passwordVerifySchema,
  passwordResetSchema,
  verificationRequestSchema,
  verificationConfirmSchema,
};
