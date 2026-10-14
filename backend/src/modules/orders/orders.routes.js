import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { paginated, toOrder } from '../../utils/serialize.js';
import { pagination, validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { buyerOnly, buyerOrFarmer, farmerOnly } from '../../middleware/roles.js';
import { idempotency } from '../../middleware/idempotency.js';
import { audit } from '../../services/audit.js';
import { createOrderSchema, idParamSchema } from './orders.schemas.js';
import {
  cancelOrder,
  completeOrder,
  confirmOrder,
  createOrder,
  getOrder,
  listOrders,
  rejectOrder,
} from './orders.service.js';

const router = Router();

router.use(authenticate);

/**
 * POST /orders — buyer places an order.
 * `idempotency` replays the original response for a repeated Idempotency-Key so a
 * double-tap or retry-after-timeout can never create two orders.
 */
router.post(
  '/',
  buyerOnly,
  idempotency('orders.create'),
  validate(createOrderSchema, 'body'),
  asyncHandler(async (req, res) => {
    const { listingId, quantity, note } = req.body;
    const order = await createOrder({ userId: req.user.id, listingId, quantity, note });

    await audit(req, 'ORDER_PLACED', {
      entityType: 'order',
      entityId: order.id,
      metadata: { listingId, quantity },
    });

    res.status(201).json(toOrder(order));
  }),
);

/** GET /orders — own orders (buyer) or orders on own listings (farmer). */
router.get(
  '/',
  buyerOrFarmer,
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const { rows, total } = await listOrders({
      userId: req.user.id,
      role: req.user.role,
      pageSize,
      offset,
    });
    res.json(paginated(rows.map(toOrder), { page, pageSize, total }));
  }),
);

/** GET /orders/:id — visible only to its buyer or its farmer. */
router.get(
  '/:id',
  buyerOrFarmer,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const order = await getOrder({ userId: req.user.id, orderId: req.params.id });
    res.json(toOrder(order));
  }),
);

/** POST /orders/:id/confirm — farmer confirms a PENDING order. */
router.post(
  '/:id/confirm',
  farmerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const order = await confirmOrder({ userId: req.user.id, orderId: req.params.id });
    await audit(req, 'ORDER_CONFIRMED', { entityType: 'order', entityId: order.id });
    res.json(toOrder(order));
  }),
);

/** POST /orders/:id/reject — farmer declines a PENDING order; stock released. */
router.post(
  '/:id/reject',
  farmerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const order = await rejectOrder({ userId: req.user.id, orderId: req.params.id });
    await audit(req, 'ORDER_REJECTED', { entityType: 'order', entityId: order.id });
    res.json(toOrder(order));
  }),
);

/** POST /orders/:id/cancel — buyer cancels their own PENDING order. */
router.post(
  '/:id/cancel',
  buyerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const order = await cancelOrder({ userId: req.user.id, orderId: req.params.id });
    await audit(req, 'ORDER_CANCELLED', { entityType: 'order', entityId: order.id });
    res.json(toOrder(order));
  }),
);

/** POST /orders/:id/complete — farmer completes a CONFIRMED order. */
router.post(
  '/:id/complete',
  farmerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const order = await completeOrder({ userId: req.user.id, orderId: req.params.id });
    await audit(req, 'ORDER_COMPLETED', { entityType: 'order', entityId: order.id });
    res.json(toOrder(order));
  }),
);

export default router;
