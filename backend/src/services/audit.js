import { query } from '../db/index.js';

/** Append-only security audit trail. Never store passwords, tokens or hashes. */
export async function audit(req, action, { entityType, entityId, metadata } = {}) {
  try {
    await query(
      `INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, metadata, ip, user_agent)
       VALUES ($1,$2,$3,$4,$5,$6,$7)`,
      [
        req?.user?.id ?? null,
        action,
        entityType ?? null,
        entityId ?? null,
        metadata ? JSON.stringify(metadata) : null,
        req?.ip ?? null,
        req?.headers?.['user-agent'] ?? null,
      ],
    );
  } catch {
    // Auditing must never break the request path.
  }
}

export default audit;
