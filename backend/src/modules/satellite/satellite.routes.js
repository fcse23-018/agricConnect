import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmOwnerOnly, farmOwnerOrFarmer } from '../../middleware/roles.js';
import { resolveOwnerFarmerId } from '../farm/records.service.js';
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
  adviceForNdvi,
} from './satellite.service.js';
import monitoringRoutes from './satellite.monitoring.routes.js';

const router = Router();

// Reading satellite history is available to whoever may read the plot — the owner
// and the farmer employed to work it — matching `GET /farms/plots`. Requesting a NEW
// computation is the owner's job (it consumes provider quota and needs the boundary),
// so `POST /ndvi` carries its own `farmOwnerOnly` guard below.
//
// Each read resolves the *owner's* farmer id, so an employed farmer sees the
// employer's readings and a caller linked to no farm is refused.
router.use(authenticate, farmOwnerOrFarmer);

/**
 * Monitoring (overview, boundary, weather, events, alerts) declares its own
 * read/write guards internally, so it is mounted here under the same
 * authentication as the NDVI endpoints. It is declared first only because its
 * paths are more specific.
 */
router.use('/', monitoringRoutes);

/**
 * POST /satellite/ndvi
 *
 * A cloud-covered observation returns 201 with status FAILED — that is an
 * expected outcome, not a server error. A fabricated NDVI value is never
 * returned.
 *
 * A GENERATED result also carries an `advice` block (trend versus the plot's
 * previous reading, prioritised actions, what to scout for, and how far the
 * number can be trusted). It is built here, once, so every client shows the same
 * guidance for the same observation instead of each re-deriving it.
 */
router.post(
  '/ndvi',
  farmOwnerOnly,
  validate(createNdviSchema),
  asyncHandler(async (req, res) => {
    const row = await requestNdvi({
      farmerId: req.user.id,
      plotId: req.body.plotId,
      geoJsonBoundary: req.body.geoJsonBoundary,
    });
    res.status(201).json(presentNdvi(row, await adviceForNdvi(row)));
  }),
);

// `/ndvi/latest` must be declared before `/ndvi/:id` so "latest" is not parsed
// as an id.
router.get(
  '/ndvi/latest',
  validate(latestQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const row = await latestNdvi(await resolveOwnerFarmerId(req.user), req.query.plotId);
    // Advice travels with the cached reading so an offline client still has the
    // guidance, not just the number.
    res.json({ ndvi: row ? presentNdvi(row, await adviceForNdvi(row)) : null });
  }),
);

router.get(
  '/ndvi',
  validate(historyQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listNdvi(await resolveOwnerFarmerId(req.user), {
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
    const row = await getNdvi(await resolveOwnerFarmerId(req.user), req.params.id);
    if (!row) throw notFound('NDVI record not found.');
    res.json(presentNdvi(row, await adviceForNdvi(row)));
  }),
);

export default router;
