import { Router } from 'express';

import { many, one, withTransaction } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmerOnly } from '../../middleware/roles.js';
import { notFound } from '../../utils/errors.js';
import { toSale, toExpense, paginated, num } from '../../utils/serialize.js';
import {
  createSaleSchema,
  updateSaleSchema,
  createExpenseSchema,
  updateExpenseSchema,
  listQuerySchema,
  summaryQuerySchema,
  idParamSchema,
} from './finance.schemas.js';

const router = Router();

/**
 * Financials are a farmer-only capability. Every route below inherits this
 * guard, so an employee or buyer receives 403 FORBIDDEN — this is an explicit,
 * tested acceptance criterion (docs/02-API-CONTRACT.md §2.5).
 */
router.use(authenticate, farmerOnly);

const BWP = 'BWP';

function appendRange(clauses, params, column, { from, to }) {
  if (from) {
    params.push(from);
    clauses.push(`${column} >= $${params.length}`);
  }
  if (to) {
    params.push(to);
    clauses.push(`${column} <= $${params.length}`);
  }
}

/* --------------------------------- sales --------------------------------- */

router.get(
  '/sales',
  validate(listQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const clauses = ['farmer_id = $1', 'deleted_at IS NULL'];
    const params = [req.user.id];
    appendRange(clauses, params, 'sale_date', { from: req.query.from, to: req.query.to });
    const where = clauses.join(' AND ');

    const totalRow = await one(`SELECT COUNT(*)::int AS total FROM sale_transactions WHERE ${where}`, params);
    const rows = await many(
      `SELECT * FROM sale_transactions WHERE ${where}
        ORDER BY sale_date DESC, created_at DESC
        LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, pageSize, offset],
    );
    res.json(paginated(rows.map(toSale), { page, pageSize, total: totalRow.total }));
  }),
);

router.post(
  '/sales',
  validate(createSaleSchema),
  asyncHandler(async (req, res) => {
    const data = req.body;
    const { row, created } = await withTransaction(async (client) => {
      if (data.clientId) {
        const existing = await client.query(
          `SELECT id FROM sale_transactions WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
          [req.user.id, data.clientId],
        );
        if (existing.rows[0]) {
          const updated = await client.query(
            `UPDATE sale_transactions
                SET item = $1, quantity = $2, unit_price = $3, amount = $4,
                    buyer_name = $5, sale_date = $6, notes = $7, deleted_at = NULL
              WHERE id = $8 AND farmer_id = $9
              RETURNING *`,
            [data.item, data.quantity, data.unitPrice ?? null, data.amount, data.buyerName ?? null, data.date, data.notes ?? null, existing.rows[0].id, req.user.id],
          );
          return { row: updated.rows[0], created: false };
        }
      }

      const inserted = await client.query(
        `INSERT INTO sale_transactions (farmer_id, item, quantity, unit_price, amount, currency, buyer_name, sale_date, notes, client_id)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
         RETURNING *`,
        [req.user.id, data.item, data.quantity, data.unitPrice ?? null, data.amount, BWP, data.buyerName ?? null, data.date, data.notes ?? null, data.clientId ?? null],
      );
      return { row: inserted.rows[0], created: true };
    });

    res.status(created ? 201 : 200).json(toSale(row));
  }),
);

router.get(
  '/sales/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `SELECT * FROM sale_transactions WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('Sale not found.');
    res.json(toSale(row));
  }),
);

router.patch(
  '/sales/:id',
  validate(idParamSchema, 'params'),
  validate(updateSaleSchema),
  asyncHandler(async (req, res) => {
    const body = req.body;
    const assignments = [];
    const params = [];
    const push = (column, value) => {
      params.push(value);
      assignments.push(`${column} = $${params.length}`);
    };
    if ('item' in body) push('item', body.item);
    if ('quantity' in body) push('quantity', body.quantity);
    if ('unitPrice' in body) push('unit_price', body.unitPrice);
    if ('amount' in body) push('amount', body.amount);
    if ('buyerName' in body) push('buyer_name', body.buyerName);
    if ('date' in body) push('sale_date', body.date);
    if ('notes' in body) push('notes', body.notes);

    params.push(req.params.id);
    const idPlaceholder = `$${params.length}`;
    params.push(req.user.id);
    const farmerPlaceholder = `$${params.length}`;

    const row = await one(
      `UPDATE sale_transactions SET ${assignments.join(', ')}
        WHERE id = ${idPlaceholder} AND farmer_id = ${farmerPlaceholder} AND deleted_at IS NULL
        RETURNING *`,
      params,
    );
    if (!row) throw notFound('Sale not found.');
    res.json(toSale(row));
  }),
);

router.delete(
  '/sales/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `UPDATE sale_transactions SET deleted_at = now()
        WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL
        RETURNING id`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('Sale not found.');
    res.status(204).end();
  }),
);

/* -------------------------------- expenses ------------------------------- */

router.get(
  '/expenses',
  validate(listQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const clauses = ['farmer_id = $1', 'deleted_at IS NULL'];
    const params = [req.user.id];
    appendRange(clauses, params, 'expense_date', { from: req.query.from, to: req.query.to });
    const where = clauses.join(' AND ');

    const totalRow = await one(`SELECT COUNT(*)::int AS total FROM expenses WHERE ${where}`, params);
    const rows = await many(
      `SELECT * FROM expenses WHERE ${where}
        ORDER BY expense_date DESC, created_at DESC
        LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, pageSize, offset],
    );
    res.json(paginated(rows.map(toExpense), { page, pageSize, total: totalRow.total }));
  }),
);

router.post(
  '/expenses',
  validate(createExpenseSchema),
  asyncHandler(async (req, res) => {
    const data = req.body;
    const { row, created } = await withTransaction(async (client) => {
      if (data.clientId) {
        const existing = await client.query(
          `SELECT id FROM expenses WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
          [req.user.id, data.clientId],
        );
        if (existing.rows[0]) {
          const updated = await client.query(
            `UPDATE expenses
                SET category = $1, description = $2, amount = $3, expense_date = $4, deleted_at = NULL
              WHERE id = $5 AND farmer_id = $6
              RETURNING *`,
            [data.category, data.description ?? null, data.amount, data.expenseDate, existing.rows[0].id, req.user.id],
          );
          return { row: updated.rows[0], created: false };
        }
      }

      const inserted = await client.query(
        `INSERT INTO expenses (farmer_id, category, description, amount, currency, expense_date, client_id)
         VALUES ($1,$2,$3,$4,$5,$6,$7)
         RETURNING *`,
        [req.user.id, data.category, data.description ?? null, data.amount, BWP, data.expenseDate, data.clientId ?? null],
      );
      return { row: inserted.rows[0], created: true };
    });

    res.status(created ? 201 : 200).json(toExpense(row));
  }),
);

router.get(
  '/expenses/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `SELECT * FROM expenses WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('Expense not found.');
    res.json(toExpense(row));
  }),
);

router.patch(
  '/expenses/:id',
  validate(idParamSchema, 'params'),
  validate(updateExpenseSchema),
  asyncHandler(async (req, res) => {
    const body = req.body;
    const assignments = [];
    const params = [];
    const push = (column, value) => {
      params.push(value);
      assignments.push(`${column} = $${params.length}`);
    };
    if ('category' in body) push('category', body.category);
    if ('description' in body) push('description', body.description);
    if ('amount' in body) push('amount', body.amount);
    if ('expenseDate' in body) push('expense_date', body.expenseDate);

    params.push(req.params.id);
    const idPlaceholder = `$${params.length}`;
    params.push(req.user.id);
    const farmerPlaceholder = `$${params.length}`;

    const row = await one(
      `UPDATE expenses SET ${assignments.join(', ')}
        WHERE id = ${idPlaceholder} AND farmer_id = ${farmerPlaceholder} AND deleted_at IS NULL
        RETURNING *`,
      params,
    );
    if (!row) throw notFound('Expense not found.');
    res.json(toExpense(row));
  }),
);

router.delete(
  '/expenses/:id',
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `UPDATE expenses SET deleted_at = now()
        WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL
        RETURNING id`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('Expense not found.');
    res.status(204).end();
  }),
);

/* --------------------------------- summary ------------------------------- */

/**
 * GET /finance/summary?from&to
 *
 * Totals and the monthly series are computed in SQL (`SUM`) from real rows
 * only. When from/to are omitted the range is derived from the farmer's own
 * data; months with no activity are zero-filled so the series always spans the
 * requested window. Empty data therefore returns zeroes, never fabricated
 * figures.
 */
router.get(
  '/summary',
  validate(summaryQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const farmerId = req.user.id;

    const bounds = await one(
      `SELECT
         to_char(LEAST(
           COALESCE((SELECT MIN(sale_date) FROM sale_transactions WHERE farmer_id = $1 AND deleted_at IS NULL), CURRENT_DATE),
           COALESCE((SELECT MIN(expense_date) FROM expenses WHERE farmer_id = $1 AND deleted_at IS NULL), CURRENT_DATE)
         ), 'YYYY-MM-DD') AS min_date,
         to_char(GREATEST(
           COALESCE((SELECT MAX(sale_date) FROM sale_transactions WHERE farmer_id = $1 AND deleted_at IS NULL), CURRENT_DATE),
           COALESCE((SELECT MAX(expense_date) FROM expenses WHERE farmer_id = $1 AND deleted_at IS NULL), CURRENT_DATE)
         ), 'YYYY-MM-DD') AS max_date`,
      [farmerId],
    );

    let from = req.query.from ?? bounds.min_date;
    let to = req.query.to ?? bounds.max_date;
    if (from > to) [from, to] = [to, from];

    const totals = await one(
      `SELECT
         COALESCE((SELECT SUM(amount) FROM sale_transactions
                    WHERE farmer_id = $1 AND deleted_at IS NULL AND sale_date BETWEEN $2::date AND $3::date), 0) AS total_sales,
         COALESCE((SELECT SUM(amount) FROM expenses
                    WHERE farmer_id = $1 AND deleted_at IS NULL AND expense_date BETWEEN $2::date AND $3::date), 0) AS total_expenses`,
      [farmerId, from, to],
    );

    const series = await many(
      `WITH months AS (
         SELECT generate_series(date_trunc('month', $2::date), date_trunc('month', $3::date), interval '1 month') AS month_start
       )
       SELECT to_char(m.month_start, 'YYYY-MM') AS period,
              COALESCE(s.total, 0) AS sales,
              COALESCE(e.total, 0) AS expenses
         FROM months m
         LEFT JOIN (
           SELECT to_char(sale_date, 'YYYY-MM') AS period, SUM(amount) AS total
             FROM sale_transactions
            WHERE farmer_id = $1 AND deleted_at IS NULL AND sale_date BETWEEN $2::date AND $3::date
            GROUP BY 1
         ) s ON s.period = to_char(m.month_start, 'YYYY-MM')
         LEFT JOIN (
           SELECT to_char(expense_date, 'YYYY-MM') AS period, SUM(amount) AS total
             FROM expenses
            WHERE farmer_id = $1 AND deleted_at IS NULL AND expense_date BETWEEN $2::date AND $3::date
            GROUP BY 1
         ) e ON e.period = to_char(m.month_start, 'YYYY-MM')
        ORDER BY m.month_start ASC`,
      [farmerId, from, to],
    );

    const totalSales = num(totals.total_sales);
    const totalExpenses = num(totals.total_expenses);

    res.json({
      totalSales,
      totalExpenses,
      netBalance: num(Number(totalSales) - Number(totalExpenses)),
      currency: BWP,
      series: series.map((row) => ({
        period: row.period,
        sales: num(row.sales),
        expenses: num(row.expenses),
      })),
    });
  }),
);

export default router;
