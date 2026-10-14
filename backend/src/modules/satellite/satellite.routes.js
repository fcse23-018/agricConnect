import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmerOnly } from '../../middleware/roles.js';
import { notFound } from '../../utils/errors.js';
import { paginated } from '../../utils/serialize.js';
import {
  createNdviSchema,
  historyQuerySchema,
  latestQuerySchema,
  idParamSchema,
} from './satellite.schemas.js';
import {
  requestNdvi,
  listNdvi,
  getNdvi,
  latestNdvi,
  presentNdvi,
} from './satellite.service.js';

const router = Router();

// NDVI is a farmer-only capability (permission matrix).
router.use(authenticate, farmerOnly);

/**
 * POST /satellite/ndvi
 *
 * A cloud-covered observation returns 201 with status FAILED — that is an
 * expected outcome, not a server error. A fabricated NDVI value is never
 * returned.
 */
router.post(
  '/ndvi',
  validate(createNdviSchema),
  asyncHandler(async (req, res) => {
    const row = await requestNdvi({
      farmerId: req.user.id,
      plotId: req.body.plotId,
      geoJsonBoundary: req.body.geoJsonBoundary,
    });
    res.status(201).json(presentNdvi(row));
  }),
);

// `/ndvi/latest` must be declared before `/ndvi/:id` so "latest" is not parsed
// as an id.
router.get(
  '/ndvi/latest',
  validate(latestQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const row = await latestNdvi(req.user.id, req.query.plotId);
    res.json({ ndvi: row ? presentNdvi(row) : null });
  }),
);

router.get(
  '/ndvi',
  validate(historyQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listNdvi(req.user.id, {
      plotId: req.query.plotId,
      limit: pageSize,
      offset,
    });
    res.json(paginated(items.map(presentNdvi), { page, pageSize, total }));
  }),
);

router.get(
  '/ndvi/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await getNdvi(req.user.id, req.params.id);
    if (!row) throw notFound('NDVI record not found.');
    res.json(presentNdvi(row));
  }),
);

export default router;
