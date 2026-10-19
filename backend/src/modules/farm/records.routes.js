import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmOwnerOrFarmer } from '../../middleware/roles.js';
import { notFound } from '../../utils/errors.js';
import { toHarvest, toLivestock, paginated } from '../../utils/serialize.js';
import {
  createHarvestSchema,
  updateHarvestSchema,
  listHarvestQuerySchema,
  createLivestockSchema,
  updateLivestockSchema,
  listLivestockQuerySchema,
  idParamSchema,
} from './records.schemas.js';
import {
  resolveOwnerFarmerId,
  listHarvests,
  getHarvest,
  createHarvest,
  updateHarvest,
  deleteHarvest,
  listLivestock,
  getLivestock,
  createLivestock,
  updateLivestock,
  deleteLivestock,
} from './records.service.js';

const router = Router();

// Both the owner and the farmer who works the land record field data: the owner on
// their own account, the farmer on behalf of the owner who employs them (see
// `resolveOwnerFarmerId`). Buyers have no access at all.
router.use(authenticate, farmOwnerOrFarmer);

/* ------------------------------- harvests -------------------------------- */

router.get(
  '/harvests',
  validate(listHarvestQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listHarvests(farmerId, {
      from: req.query.from,
      to: req.query.to,
      cropType: req.query.cropType,
      limit: pageSize,
      offset,
    });
    res.json(paginated(items.map(toHarvest), { page, pageSize, total }));
  }),
);

router.post(
  '/harvests',
  validate(createHarvestSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { row, created } = await createHarvest(farmerId, req.user.id, req.body);
    res.status(created ? 201 : 200).json(toHarvest(row));
  }),
);

router.get(
  '/harvests/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await getHarvest(farmerId, req.params.id);
    if (!row) throw notFound('Harvest record not found.');
    res.json(toHarvest(row));
  }),
);

router.patch(
  '/harvests/:id',
  validate(idParamSchema, 'params'),
  validate(updateHarvestSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await updateHarvest(farmerId, req.params.id, req.body);
    if (!row) throw notFound('Harvest record not found.');
    res.json(toHarvest(row));
  }),
);

router.delete(
  '/harvests/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await deleteHarvest(farmerId, req.params.id);
    if (!row) throw notFound('Harvest record not found.');
    res.status(204).end();
  }),
);

/* ------------------------------- livestock ------------------------------- */

router.get(
  '/livestock',
  validate(listLivestockQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listLivestock(farmerId, {
      from: req.query.from,
      to: req.query.to,
      limit: pageSize,
      offset,
    });
    res.json(paginated(items.map(toLivestock), { page, pageSize, total }));
  }),
);

router.post(
  '/livestock',
  validate(createLivestockSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { row, created } = await createLivestock(farmerId, req.user.id, req.body);
    res.status(created ? 201 : 200).json(toLivestock(row));
  }),
);

router.get(
  '/livestock/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await getLivestock(farmerId, req.params.id);
    if (!row) throw notFound('Livestock record not found.');
    res.json(toLivestock(row));
  }),
);

router.patch(
  '/livestock/:id',
  validate(idParamSchema, 'params'),
  validate(updateLivestockSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await updateLivestock(farmerId, req.params.id, req.body);
    if (!row) throw notFound('Livestock record not found.');
    res.json(toLivestock(row));
  }),
);

router.delete(
  '/livestock/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await deleteLivestock(farmerId, req.params.id);
    if (!row) throw notFound('Livestock record not found.');
    res.status(204).end();
  }),
);

export default router;
