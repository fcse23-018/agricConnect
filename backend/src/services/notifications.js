import { query } from '../db/index.js';

/**
 * In-app notification feed. Respects each user's notification preferences.
 * Also acceptable to call with a transaction client as the first argument's
 * executor is not needed here (notifications are not part of order transactions).
 */
export async function notify({ userId, type, title, body, relatedEntityType, relatedEntityId }) {
  try {
    const res = await query(
      `INSERT INTO notifications (user_id, type, title, body, related_entity_type, related_entity_id)
       VALUES ($1,$2,$3,$4,$5,$6) RETURNING id`,
      [userId, type, title, body, relatedEntityType ?? null, relatedEntityId ?? null],
    );
    return res.rows[0].id;
  } catch {
    // A failed notification must never roll back a business operation.
    return null;
  }
}

/** Maps a notification type to the user preference flag that governs it. */
const PREFERENCE_BY_TYPE = {
  ORDER_PLACED: 'orders',
  ORDER_CONFIRMED: 'orders',
  ORDER_REJECTED: 'orders',
  ORDER_CANCELLED: 'orders',
  NDVI_READY: 'satellite',
  SYNC_ISSUE: 'sync',
  MARKETPLACE: 'marketplace',
};

export function preferenceFor(type) {
  return PREFERENCE_BY_TYPE[type] ?? null;
}

export default notify;
