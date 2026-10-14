import { config } from '../../config/index.js';
import { one, many, withTransaction } from '../../db/index.js';
import { randomToken, pseudonymFor } from '../../utils/crypto.js';
import { dateOnly } from '../../utils/serialize.js';

/**
 * Account-deletion lifecycle.
 *
 * Retention rationale (see also the requirement that financial history be kept
 * for legal/tax purposes):
 *  - Personal data (name, phone, email, avatar, notification preferences) is
 *    anonymised, and locally-owned private field data (plots, harvests,
 *    livestock, satellite NDVI) is deleted outright.
 *  - Financial and order records (`sale_transactions`, `expenses`, `orders`,
 *    `produce_listings`) and `audit_log` are retained in de-identified form:
 *    their `farmer_id`/`buyer_id` still reference this user row, which after
 *    anonymisation holds no personal data. A non-reversible pseudonym links the
 *    retention-ledger entries.
 *
 * `processDueDeletions` is the entry point invoked by a scheduled job / CLI
 * (see the deployment docs); it is not called from the request path.
 */

/**
 * `users.phone_number` is NOT NULL with an E.164 CHECK, so it cannot literally
 * be set to NULL. A reserved, non-routable placeholder releases the real number
 * (the partial unique index only covers live rows) while satisfying the schema.
 */
const DELETED_PHONE_PLACEHOLDER = '+10000000000';

/**
 * What we retain, why, and for how long. Audit retention shares the financial
 * horizon here for simplicity; adjust per legal advice (docs §8 U5).
 */
const RETENTION_CATEGORIES = [
  {
    category: 'FINANCIAL',
    justification:
      'Sale transactions and farm expenses are retained in de-identified form to meet tax and accounting record-keeping obligations (Botswana Data Protection Act).',
  },
  {
    category: 'ORDERS',
    justification:
      'Marketplace orders are retained in de-identified form to evidence completed commercial transactions and resolve disputes.',
  },
  {
    category: 'AUDIT',
    justification:
      'Security and access audit records are retained to demonstrate accountability and to detect and investigate abuse.',
  },
];

function retentionDate(months) {
  const d = new Date();
  d.setUTCMonth(d.getUTCMonth() + months);
  return d.toISOString().slice(0, 10);
}

function toRetention(row) {
  return {
    dataCategory: row.data_category,
    justification: row.justification,
    retainUntil: dateOnly(row.retain_until),
  };
}

async function listRetention(userId) {
  const rows = await many(
    // `retain_until::text` avoids a local-midnight Date being shifted a day by UTC conversion.
    `SELECT data_category, justification, retain_until::text AS retain_until
       FROM retention_ledger WHERE subject_user = $1 ORDER BY data_category`,
    [userId],
  );
  return rows.map(toRetention);
}

/**
 * Record a deletion request and start the grace period. Shared by the
 * authenticated flow and the public deletion page so the two can never diverge.
 *
 * Idempotent: a user with an existing PENDING request gets that request back
 * (the partial unique index `deletion_one_active_per_user` enforces this even
 * under a race, which is caught below).
 */
export async function createDeletionRequest({ userId, reason }) {
  const existing = await one(
    `SELECT * FROM account_deletion_requests
      WHERE user_id = $1 AND status = 'PENDING'
      ORDER BY requested_at DESC LIMIT 1`,
    [userId],
  );
  if (existing) {
    return { request: existing, retention: await listRetention(userId), created: false };
  }

  const pseudonym = pseudonymFor(userId);
  const effectiveAt = new Date(Date.now() + config.account.deletionGraceDays * 86_400_000);
  const retainUntil = retentionDate(config.account.financialRetentionMonths);

  try {
    const request = await withTransaction(async (client) => {
      const inserted = await client.query(
        `INSERT INTO account_deletion_requests (user_id, reason, status, effective_at)
         VALUES ($1,$2,'PENDING',$3) RETURNING *`,
        [userId, reason ?? null, effectiveAt],
      );
      await client.query(
        `UPDATE users SET account_status = 'PENDING_DELETION' WHERE id = $1 AND deleted_at IS NULL`,
        [userId],
      );
      // A pending deletion invalidates every active session immediately.
      await client.query(
        `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
        [userId],
      );
      for (const entry of RETENTION_CATEGORIES) {
        await client.query(
          `INSERT INTO retention_ledger (subject_user, data_category, justification, retain_until, pseudonym)
           VALUES ($1,$2,$3,$4,$5)`,
          [userId, entry.category, entry.justification, retainUntil, pseudonym],
        );
      }
      return inserted.rows[0];
    });
    return { request, retention: await listRetention(userId), created: true };
  } catch (err) {
    // Lost the race against a concurrent request: return the winning row.
    if (err && err.code === '23505') {
      const raced = await one(
        `SELECT * FROM account_deletion_requests
          WHERE user_id = $1 AND status = 'PENDING'
          ORDER BY requested_at DESC LIMIT 1`,
        [userId],
      );
      if (raced) return { request: raced, retention: await listRetention(userId), created: false };
    }
    throw err;
  }
}

/**
 * Irreversibly anonymise a user and purge their private field data. Runs in a
 * single transaction so a crash never leaves a half-anonymised account.
 */
export async function anonymizeUser(userId) {
  return withTransaction(async (client) => {
    const found = await client.query(`SELECT id FROM users WHERE id = $1 FOR UPDATE`, [userId]);

    if (found.rowCount === 0) {
      // User already gone; just close out any open request.
      await client.query(
        `UPDATE account_deletion_requests
            SET status = 'COMPLETED', anonymised_at = now(), completed_at = now()
          WHERE user_id = $1 AND status IN ('PENDING','PROCESSING')`,
        [userId],
      );
      return { userId, anonymised: false };
    }

    const pseudonym = pseudonymFor(userId);

    // 1. Strip all personal and credential material from the user row.
    await client.query(
      `UPDATE users
          SET full_name = 'Deleted user',
              phone_number = $2,
              email = NULL,
              password_hash = $3,
              avatar_url = NULL,
              notification_preferences = '{}'::jsonb,
              account_status = 'DELETED',
              deleted_at = now(),
              employer_farmer_id = NULL,
              failed_login_count = 0,
              locked_until = NULL
        WHERE id = $1`,
      [userId, DELETED_PHONE_PLACEHOLDER, randomToken(48)],
    );

    // 2. Destroy session and verification material.
    await client.query(`DELETE FROM refresh_tokens WHERE user_id = $1`, [userId]);
    await client.query(`DELETE FROM device_tokens WHERE user_id = $1`, [userId]);
    await client.query(`DELETE FROM verification_tokens WHERE user_id = $1 AND consumed_at IS NULL`, [userId]);

    // 3. Delete locally-owned private field data (satellite rows reference plots).
    await client.query(`DELETE FROM satellite_ndvi WHERE farmer_id = $1`, [userId]);
    await client.query(`DELETE FROM harvest_records WHERE farmer_id = $1`, [userId]);
    await client.query(`DELETE FROM livestock_counts WHERE farmer_id = $1`, [userId]);
    await client.query(`DELETE FROM farm_plots WHERE farmer_id = $1`, [userId]);

    // 4. Record (once) what is retained and for how long, keyed by pseudonym.
    const retainUntil = retentionDate(config.account.financialRetentionMonths);
    for (const entry of RETENTION_CATEGORIES) {
      await client.query(
        `INSERT INTO retention_ledger (subject_user, data_category, justification, retain_until, pseudonym)
         SELECT $1,$2,$3,$4,$5
          WHERE NOT EXISTS (
            SELECT 1 FROM retention_ledger WHERE subject_user = $1 AND data_category = $2
          )`,
        [userId, entry.category, entry.justification, retainUntil, pseudonym],
      );
    }

    // 5. Close the request.
    await client.query(
      `UPDATE account_deletion_requests
          SET status = 'COMPLETED', anonymised_at = now(), completed_at = now()
        WHERE user_id = $1 AND status IN ('PENDING','PROCESSING')`,
      [userId],
    );

    return { userId, anonymised: true, pseudonym };
  });
}

/**
 * Process every deletion request whose grace period has elapsed. Returns a
 * summary so the scheduled job can alert on failures without aborting the batch.
 */
export async function processDueDeletions() {
  const due = await many(
    `SELECT user_id FROM account_deletion_requests
      WHERE status = 'PENDING' AND effective_at <= now()
      ORDER BY effective_at ASC
      LIMIT 500`,
  );

  let processed = 0;
  let failed = 0;
  for (const row of due) {
    try {
      await anonymizeUser(row.user_id);
      processed += 1;
    } catch {
      // Never let one bad row stop the batch; the caller should monitor `failed`.
      failed += 1;
    }
  }
  return { processed, failed };
}

export default { anonymizeUser, processDueDeletions, createDeletionRequest };
