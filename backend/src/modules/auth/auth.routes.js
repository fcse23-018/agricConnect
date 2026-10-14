import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { authLimiter } from '../../middleware/rateLimit.js';
import { idempotency } from '../../middleware/idempotency.js';
import {
  registerSchema,
  loginSchema,
  refreshSchema,
  logoutSchema,
  passwordForgotSchema,
  passwordVerifySchema,
  passwordResetSchema,
  verificationRequestSchema,
  verificationConfirmSchema,
} from './auth.schemas.js';
import {
  serializeUser,
  registerUser,
  loginUser,
  rotateRefreshToken,
  revokeRefreshToken,
  revokeAllRefreshTokens,
  findUserById,
  requestPasswordReset,
  verifyPasswordResetCode,
  resetPassword,
  requestVerificationCode,
  confirmVerification,
} from './auth.service.js';

// Mounted at /auth by modules/index.js — paths below are relative to that.
const router = Router();

router.post(
  '/register',
  authLimiter,
  validate(registerSchema, 'body'),
  // Idempotency runs after validation so a malformed retry is never cached.
  idempotency('auth.register'),
  asyncHandler(async (req, res) => {
    const { fullName, phoneNumber, email, password, role } = req.body;
    const result = await registerUser({ fullName, phoneNumber, email, password, role }, req);
    const { user, accessToken, refreshToken, expiresIn, devVerificationCode } = result;
    res.status(201).json({
      user: serializeUser(user),
      accessToken,
      refreshToken,
      expiresIn,
      // Development-only field; omitted entirely unless EXPOSE_DEV_CODES is set.
      ...(devVerificationCode ? { devVerificationCode } : {}),
    });
  }),
);

router.post(
  '/login',
  authLimiter,
  validate(loginSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { identifier, password } = req.body;
    const { user, accessToken, refreshToken, expiresIn } = await loginUser({ identifier, password }, req);
    res.json({ user: serializeUser(user), accessToken, refreshToken, expiresIn });
  }),
);

router.post(
  '/refresh',
  authLimiter,
  validate(refreshSchema, 'body'),
  asyncHandler(async (req, res) => {
    const tokens = await rotateRefreshToken(req.body.refreshToken, req);
    res.json(tokens);
  }),
);

router.post(
  '/logout',
  authLimiter,
  authenticate,
  validate(logoutSchema, 'body'),
  asyncHandler(async (req, res) => {
    await revokeRefreshToken(req.user.id, req.body.refreshToken);
    res.status(204).end();
  }),
);

router.post(
  '/logout-all',
  authLimiter,
  authenticate,
  asyncHandler(async (req, res) => {
    await revokeAllRefreshTokens(req.user.id);
    res.status(204).end();
  }),
);

router.get(
  '/me',
  authenticate,
  asyncHandler(async (req, res) => {
    const user = await findUserById(req.user.id);
    res.json({ user: serializeUser(user) });
  }),
);

router.post(
  '/password/forgot',
  authLimiter,
  validate(passwordForgotSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { message, devCode } = await requestPasswordReset(req.body.identifier, req);
    res.status(202).json({ message, ...(devCode ? { devCode } : {}) });
  }),
);

router.post(
  '/password/verify',
  authLimiter,
  validate(passwordVerifySchema, 'body'),
  asyncHandler(async (req, res) => {
    const { resetToken } = await verifyPasswordResetCode(req.body.identifier, req.body.code, req);
    res.json({ resetToken });
  }),
);

router.post(
  '/password/reset',
  authLimiter,
  validate(passwordResetSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { message } = await resetPassword(req.body.resetToken, req.body.newPassword, req);
    res.json({ message });
  }),
);

router.post(
  '/verification/request',
  authLimiter,
  validate(verificationRequestSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { message, devCode } = await requestVerificationCode(req.body.identifier, req);
    res.status(202).json({ message, ...(devCode ? { devCode } : {}) });
  }),
);

router.post(
  '/verification/confirm',
  authLimiter,
  validate(verificationConfirmSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { message } = await confirmVerification(req.body.identifier, req.body.code, req);
    res.json({ message });
  }),
);

export default router;
