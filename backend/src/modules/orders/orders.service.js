import { many, one, withTransaction } from '../../db/index.js';
import { badRequest, conflict, notFound } from '../../utils/errors.js';
import { notify } from '../../services/notifications.js';

/**
 * Orders service.
 *
 * Workflow (API contract §2.8, Architecture v1.1 §9.4): a buyer places an order
 * → PENDING → the farmer is notified → the farmer confirms → CONFIRMED and the
 * listing's stock moves from reserved to sold (→ SOLD when exhausted).
 *
 * There is **no payment processing** (resolved conflict C9): an order carries a
 * computed total_price for the produce only.
 *
 * All stock arithmetic is transactional and guarded by `SELECT … FOR UPDATE` plus
 * the database CHECK `reserved_qty + sold_qty <= quantity`, so concurrent buyers
 * can never oversell a listing.
 */

const ORDER_CONTEXT_SQL = `
  SELECT o.*, pl.produce, b.full_name AS buyer_name
    FROM orders o
    JOIN produce_listings pl ON pl.id = o.listing_id
    JOIN users b ON b.id = o.buyer_id
   WHERE o.id = $1
`;

/** Row with produce + buyerName, serialisable by `toOrder`. */
async function loadContext(executor, orderId) {
  const res = await executor.query(ORDER_CONTEXT_SQL, [orderId]);
  return res.rows[0] ?? null;
}

/** Same shape, but takes a row lock on the order for the rest of the transaction. */
async function lockOrder(executor, orderId) {
  const res = await executor.query(`${ORDER_CONTEXT_SQL} FOR UPDATE OF o`, [orderId]);
  return res.rows[0] ?? null;
}

/**
 * Place an order. Stock is checked and reserved atomically.
 * Returns the persisted order row (with `produce` and `buyer_name`).
 */
export async function createOrder({ userId, listingId, quantity, note }) {
  return withTransaction(async (client) => {
    const listingRes = await client.query(
      `SELECT *
         FROM produce_listings
        WHERE id = $1 AND status = 'PUBLISHED' AND deleted_at IS NULL
        FOR UPDATE`,
      [listingId],
    );
    const listing = listingRes.rows[0];
    if (!listing) throw notFound('That listing is no longer available.');

    const available =
      Number(listing.quantity) - Number(listing.reserved_qty) - Number(listing.sold_qty);
    if (quantity <= 0) throw badRequest('Order quantity must be greater than zero.');
    if (quantity > available) {
      throw conflict('Not enough stock is available for that quantity.');
    }

    const buyerRes = await client.query(
      `SELECT full_name, phone_number FROM users WHERE id = $1`,
      [userId],
    );
    const buyer = buyerRes.rows[0];
    const buyerName = buyer?.full_name ?? 'A buyer';

    const totalPrice = Number((quantity * Number(listing.price)).toFixed(2));

    const orderRes = await client.query(
      `INSERT INTO orders
         (listing_id, buyer_id, farmer_id, quantity, unit_price, total_price, currency, status, note, buyer_contact)
       VALUES ($1,$2,$3,$4,$5,$6,$7,'PENDING',$8,$9)
       RETURNING *`,
      [
        listing.id,
        userId,
        listing.farmer_id,
        quantity,
        listing.price,
        totalPrice,
        listing.currency,
        note ?? null,
        buyer?.phone_number ?? null,
      ],
    );
    const order = orderRes.rows[0];

    // Reserve the stock immediately so a second concurrent order cannot see it.
    await client.query(
      `UPDATE produce_listings SET reserved_qty = reserved_qty + $1 WHERE id = $2`,
      [quantity, listing.id],
    );

    await notify({
      userId: listing.farmer_id,
      type: 'ORDER_PLACED',
      title: 'New order received',
      body: `${buyerName} ordered ${quantity} ${listing.unit} of ${listing.produce}.`,
      relatedEntityType: 'order',
      relatedEntityId: order.id,
    });

    return { ...order, produce: listing.produce, buyer_name: buyerName };
  });
}

/**
 * Paginated order list scoped to the caller: buyers see their own orders,
 * farmers see orders placed on their listings.
 */
export async function listOrders({ userId, role, pageSize, offset }) {
  const column = role === 'FARMER' ? 'o.farmer_id' : 'o.buyer_id';

  const totalRow = await one(
    `SELECT count(*)::int AS total FROM orders o WHERE ${column} = $1`,
    [userId],
  );

  const rows = await many(
    `SELECT o.*, pl.produce, b.full_name AS buyer_name
       FROM orders o
       JOIN produce_listings pl ON pl.id = o.listing_id
       JOIN users b ON b.id = o.buyer_id
      WHERE ${column} = $1
      ORDER BY o.created_at DESC
      LIMIT $2 OFFSET $3`,
    [userId, pageSize, offset],
  );

  return { rows, total: totalRow.total };
}

/** One order, visible only to its buyer or its farmer. Otherwise 404. */
export async function getOrder({ userId, orderId }) {
  const row = await one(`${ORDER_CONTEXT_SQL}`, [orderId]);
  if (!row) throw notFound('The requested order was not found.');
  if (row.buyer_id !== userId && row.farmer_id !== userId) {
    throw notFound('The requested order was not found.');
  }
  return row;
}

/**
 * Farmer confirms a PENDING order. Stock moves reserved → sold and the listing
 * becomes SOLD once its entire quantity has been sold.
 */
export async function confirmOrder({ userId, orderId }) {
  return withTransaction(async (client) => {
    const order = await lockOrder(client, orderId);
    if (!order || order.farmer_id !== userId) throw notFound('The requested order was not found.');
    if (order.status !== 'PENDING') throw conflict('Only a pending order can be confirmed.');

    const listingRes = await client.query(
      `SELECT * FROM produce_listings WHERE id = $1 FOR UPDATE`,
      [order.listing_id],
    );
    const listing = listingRes.rows[0];
    if (!listing) throw notFound('That listing is no longer available.');

    const qty = Number(order.quantity);

    await client.query(
      `UPDATE orders SET status = 'CONFIRMED', farmer_decision_at = now() WHERE id = $1`,
      [orderId],
    );

    // Move the held stock into sold; the CHECK constraint guarantees the sum is
    // unchanged so this can never push the listing over its declared quantity.
    const listingUpdate = await client.query(
      `UPDATE produce_listings
          SET reserved_qty = reserved_qty - $1,
              sold_qty     = sold_qty + $1
        WHERE id = $2
        RETURNING *`,
      [qty, order.listing_id],
    );
    const updatedListing = listingUpdate.rows[0];

    if (Number(updatedListing.sold_qty) >= Number(updatedListing.quantity)) {
      await client.query(`UPDATE produce_listings SET status = 'SOLD' WHERE id = $1`, [
        order.listing_id,
      ]);
    }

    await notify({
      userId: order.buyer_id,
      type: 'ORDER_CONFIRMED',
      title: 'Order confirmed',
      body: `Your order for ${qty} ${listing.unit} of ${listing.produce} was confirmed.`,
      relatedEntityType: 'order',
      relatedEntityId: orderId,
    });

    return loadContext(client, orderId);
  });
}

/** Farmer rejects a PENDING order; the held stock is released. */
export async function rejectOrder({ userId, orderId }) {
  return withTransaction(async (client) => {
    const order = await lockOrder(client, orderId);
    if (!order || order.farmer_id !== userId) throw notFound('The requested order was not found.');
    if (order.status !== 'PENDING') throw conflict('Only a pending order can be rejected.');

    const listingRes = await client.query(`SELECT * FROM produce_listings WHERE id = $1`, [
      order.listing_id,
    ]);
    const listing = listingRes.rows[0];

    await client.query(
      `UPDATE orders SET status = 'CANCELLED', farmer_decision_at = now() WHERE id = $1`,
      [orderId],
    );
    await client.query(
      `UPDATE produce_listings SET reserved_qty = reserved_qty - $1 WHERE id = $2`,
      [Number(order.quantity), order.listing_id],
    );

    await notify({
      userId: order.buyer_id,
      type: 'ORDER_REJECTED',
      title: 'Order declined',
      body: `The farmer could not fulfil your order for ${listing?.produce ?? 'the produce'}.`,
      relatedEntityType: 'order',
      relatedEntityId: orderId,
    });

    return loadContext(client, orderId);
  });
}

/** Buyer cancels their own order while it is still PENDING; stock is released. */
export async function cancelOrder({ userId, orderId }) {
  return withTransaction(async (client) => {
    const order = await lockOrder(client, orderId);
    if (!order || order.buyer_id !== userId) throw notFound('The requested order was not found.');
    if (order.status !== 'PENDING') throw conflict('This order can no longer be cancelled.');

    const listingRes = await client.query(`SELECT * FROM produce_listings WHERE id = $1`, [
      order.listing_id,
    ]);
    const listing = listingRes.rows[0];

    await client.query(`UPDATE orders SET status = 'CANCELLED' WHERE id = $1`, [orderId]);
    await client.query(
      `UPDATE produce_listings SET reserved_qty = reserved_qty - $1 WHERE id = $2`,
      [Number(order.quantity), order.listing_id],
    );

    await notify({
      userId: order.farmer_id,
      type: 'ORDER_CANCELLED',
      title: 'Order cancelled',
      body: `A buyer cancelled their order for ${listing?.produce ?? 'your produce'}.`,
      relatedEntityType: 'order',
      relatedEntityId: orderId,
    });

    return loadContext(client, orderId);
  });
}

/** Farmer marks a CONFIRMED order complete. No further stock movement. */
export async function completeOrder({ userId, orderId }) {
  return withTransaction(async (client) => {
    const order = await lockOrder(client, orderId);
    if (!order || order.farmer_id !== userId) throw notFound('The requested order was not found.');
    if (order.status !== 'CONFIRMED') throw conflict('Only a confirmed order can be completed.');

    await client.query(`UPDATE orders SET status = 'COMPLETED' WHERE id = $1`, [orderId]);
    return loadContext(client, orderId);
  });
}

export default {
  createOrder,
  listOrders,
  getOrder,
  confirmOrder,
  rejectOrder,
  cancelOrder,
  completeOrder,
};
