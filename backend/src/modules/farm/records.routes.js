import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmOwnerOrFarmer, farmOwnerOnly } from '../../middleware/roles.js';
import { notFound } from '../../utils/errors.js';
import {
  toHarvest,
  toLivestock,
  toHerdEvent,
  toHerdSummary,
  paginated,
  num,
} from '../../utils/serialize.js';
import {
  createHarvestSchema,
  updateHarvestSchema,
  listHarvestQuerySchema,
  createLivestockSchema,
  updateLivestockSchema,
  listLivestockQuerySchema,
  createHerdEventSchema,
  updateHerdEventSchema,
  listHerdEventQuerySchema,
  herdSummaryQuerySchema,
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
  listHerdEvents,
  getHerdEvent,
  createHerdEvent,
  updateHerdEvent,
  deleteHerdEvent,
  getHerdSummary,
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

/* ----------------------------- herd register ----------------------------- */

// The register is written by the same two roles that write livestock counts:
// the owner on their own account, the employed farmer on the owner's behalf.
// The shareable *summary* below is the owner's document (it merges private
// financial totals), so it carries its own stricter guard.

/**
 * GET /farm/herd/summary
 *
 * The shareable herd and financial summary (CR3): what a keeper can show a bank
 * or the Land Board. One page, computed from real rows only — current counts,
 * births/deaths/sales with money, the dipping and veterinary dates, and the
 * farm's financial totals for the same window. The summary route is declared
 * before `/herd/:id` so the literal path is not swallowed by the uuid route.
 */
router.get(
  '/herd/summary',
  farmOwnerOnly,
  validate(herdSummaryQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { register, counts, finance, range } = await getHerdSummary(farmerId, {
      from: req.query.from,
      to: req.query.to,
    });

    const totalSales = num(finance.total_sales);
    const totalExpenses = num(finance.total_expenses);

    res.json({
      generatedAt: new Date().toISOString(),
      farmer: { userId: req.user.id, fullName: req.user.fullName },
      range,
      counts: counts.map((row) => ({
        animalType: row.animal_type,
        headCount: row.head_count,
        recordDate: row.record_date ? String(row.record_date).slice(0, 10) : null,
      })),
      register: register.map(toHerdSummary),
      finance: {
        totalSales,
        totalExpenses,
        netBalance: num(Number(totalSales) - Number(totalExpenses)),
        currency: 'BWP',
      },
      note: [
        'Counts are the latest recorded snapshot per animal type. Register totals are computed from your own entries only.',
        'Register sale proceeds and the financial totals are separate records and are reported side by side, never added together.',
        'This summary is a self-declared record. It is not verified by BAITS, a bank or the Land Board.',
      ].join(' '),
    });
  }),
);

router.get(
  '/herd',
  validate(listHerdEventQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listHerdEvents(farmerId, {
      from: req.query.from,
      to: req.query.to,
      animalType: req.query.animalType,
      eventType: req.query.eventType,
      limit: pageSize,
      offset,
    });
    res.json(paginated(items.map(toHerdEvent), { page, pageSize, total }));
  }),
);

router.post(
  '/herd',
  validate(createHerdEventSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const { row, created } = await createHerdEvent(farmerId, req.user.id, req.body);
    res.status(created ? 201 : 200).json(toHerdEvent(row));
  }),
);

router.get(
  '/herd/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await getHerdEvent(farmerId, req.params.id);
    if (!row) throw notFound('Herd register entry not found.');
    res.json(toHerdEvent(row));
  }),
);

router.patch(
  '/herd/:id',
  validate(idParamSchema, 'params'),
  validate(updateHerdEventSchema),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await updateHerdEvent(farmerId, req.params.id, req.body);
    if (!row) throw notFound('Herd register entry not found.');
    res.json(toHerdEvent(row));
  }),
);

router.delete(
  '/herd/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await deleteHerdEvent(farmerId, req.params.id);
    if (!row) throw notFound('Herd register entry not found.');
    res.status(204).end();
  }),
);

export default router;
