import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { badRequest } from '../../utils/errors.js';
import { singleImage, publicUrl } from '../../services/uploads.js';

const router = Router();

/**
 * POST /uploads — store one image and return its URL path.
 *
 * The generic counterpart to `POST /users/me/avatar`: it stores the file with
 * the same pipeline (jpeg/png/webp, size cap, UUID name) but leaves the caller
 * to attach the returned URL to the record it is editing — a produce listing
 * (`imageUrl`), a farm plot, and so on. The URL is stored as a path, not an
 * absolute URL, so the storage origin can change without touching the data.
 *
 * The file is accepted under the `image` field; the response is `{ url }`.
 */
router.post(
  '/',
  authenticate,
  singleImage('image'),
  asyncHandler(async (req, res) => {
    if (!req.file) throw badRequest('An image file is required (field name "image").');
    res.status(201).json({ url: publicUrl(req.file) });
  }),
);

export default router;
