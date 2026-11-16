import { describe, it, expect } from '@jest/globals';

import {
  createSaleSchema,
  updateSaleSchema,
  createExpenseSchema,
  updateExpenseSchema,
  listQuerySchema,
  summaryQuerySchema,
  idParamSchema,
} from '../../src/modules/finance/finance.schemas.js';

/**
 * Finance schemas (objective O3 in the academic report: the finance module had
 * no unit tests).
 *
 * These are the gates in front of money records. The properties that matter:
 *  - amounts are non-negative numbers, never strings or NaN sneaking through;
 *  - dates are calendar dates (YYYY-MM-DD), because a bad date silently moves
 *    money into the wrong month of the summary series;
 *  - `clientId` participates in the offline-first idempotent upsert, so its
 *    shape is pinned too;
 *  - updates are partial but never empty, and never carry a clientId (the key
 *    cannot be re-pointed after the row exists).
 */

describe('createSaleSchema', () => {
  const base = { item: 'Maize', quantity: 10, amount: 500, date: '2026-03-01' };

  it('accepts a full sale with unit price and buyer', () => {
    const parsed = createSaleSchema.parse({
      ...base,
      unitPrice: 50,
      buyerName: 'Kgosi Stores',
      notes: 'Paid cash',
      clientId: 'sale-1',
    });
    expect(parsed).toMatchObject({ item: 'Maize', quantity: 10, unitPrice: 50, amount: 500, clientId: 'sale-1' });
  });

  it('allows unit price, buyer and notes to be omitted', () => {
    const parsed = createSaleSchema.parse(base);
    expect(parsed.unitPrice ?? null).toBeNull();
    expect(parsed.buyerName ?? null).toBeNull();
  });

  it('allows a null unit price (a total without a per-item price)', () => {
    expect(createSaleSchema.parse({ ...base, unitPrice: null }).unitPrice).toBeNull();
  });

  it('rejects a negative amount with a field-level error', () => {
    const result = createSaleSchema.safeParse({ ...base, amount: -1 });
    expect(result.success).toBe(false);
    expect(result.error.issues.map((issue) => issue.path.join('.'))).toContain('amount');
  });

  it('rejects a negative quantity with a field-level error', () => {
    const result = createSaleSchema.safeParse({ ...base, quantity: -0.5 });
    expect(result.success).toBe(false);
    expect(result.error.issues.map((issue) => issue.path.join('.'))).toContain('quantity');
  });

  it('rejects NaN', () => {
    expect(createSaleSchema.safeParse({ ...base, amount: Number.NaN }).success).toBe(false);
  });

  it('requires a YYYY-MM-DD date', () => {
    expect(createSaleSchema.safeParse({ ...base, date: 'March 1' }).success).toBe(false);
    expect(createSaleSchema.safeParse({ ...base, date: '2026/03/01' }).success).toBe(false);
  });

  it('rejects a blank item', () => {
    expect(createSaleSchema.safeParse({ ...base, item: '   ' }).success).toBe(false);
  });

  it('rejects an over-long item name (DB constraint is 120)', () => {
    expect(createSaleSchema.safeParse({ ...base, item: 'x'.repeat(121) }).success).toBe(false);
  });

  it('rejects a blank clientId', () => {
    // The offline queue relies on clientId being non-empty when present.
    expect(createSaleSchema.safeParse({ ...base, clientId: '' }).success).toBe(false);
  });
});

describe('updateSaleSchema', () => {
  it('accepts a partial update', () => {
    expect(updateSaleSchema.parse({ amount: 600 }).amount).toBe(600);
  });

  it('refuses an empty update', () => {
    expect(updateSaleSchema.safeParse({}).success).toBe(false);
  });

  it('has no clientId field: the idempotency key cannot be re-pointed', () => {
    // A clientId on an update would silently re-key another device's row.
    const parsed = updateSaleSchema.parse({ amount: 600 });
    expect(Object.prototype.hasOwnProperty.call(parsed, 'clientId')).toBe(false);
  });

  it('rejects a negative amount on update too', () => {
    expect(updateSaleSchema.safeParse({ amount: -1 }).success).toBe(false);
  });
});

describe('createExpenseSchema', () => {
  const base = { category: 'Feed', amount: 250, expenseDate: '2026-03-02' };

  it('accepts an expense with a description and client id', () => {
    const parsed = createExpenseSchema.parse({ ...base, description: 'Winter lick', clientId: 'exp-9' });
    expect(parsed.description).toBe('Winter lick');
    expect(parsed.clientId).toBe('exp-9');
  });

  it('allows the description to be null', () => {
    expect(createExpenseSchema.parse({ ...base, description: null }).description).toBeNull();
  });

  it('rejects a negative amount with a field-level error', () => {
    const result = createExpenseSchema.safeParse({ ...base, amount: -10 });
    expect(result.success).toBe(false);
    expect(result.error.issues.map((issue) => issue.path.join('.'))).toContain('amount');
  });

  it('rejects a blank category', () => {
    expect(createExpenseSchema.safeParse({ ...base, category: '' }).success).toBe(false);
  });

  it('requires a YYYY-MM-DD expense date', () => {
    expect(createExpenseSchema.safeParse({ ...base, expenseDate: '02/03/2026' }).success).toBe(false);
  });
});

describe('updateExpenseSchema', () => {
  it('accepts a partial update', () => {
    expect(updateExpenseSchema.parse({ amount: 260 }).amount).toBe(260);
  });

  it('refuses an empty update', () => {
    expect(updateExpenseSchema.safeParse({}).success).toBe(false);
  });
});

describe('listQuerySchema', () => {
  it('accepts a date range with paging', () => {
    const parsed = listQuerySchema.parse({ from: '2026-01-01', to: '2026-06-30', page: 3, pageSize: 25 });
    expect(parsed).toMatchObject({ from: '2026-01-01', to: '2026-06-30', page: 3, pageSize: 25 });
  });

  it('accepts being omitted entirely', () => {
    expect(listQuerySchema.parse({}).page ?? null).toBeNull();
  });

  it('rejects a malformed date range bound', () => {
    expect(listQuerySchema.safeParse({ from: 'yesterday' }).success).toBe(false);
  });

  it('caps pageSize at 100', () => {
    expect(listQuerySchema.safeParse({ pageSize: 500 }).success).toBe(false);
  });

  it('rejects page 0', () => {
    expect(listQuerySchema.safeParse({ page: 0 }).success).toBe(false);
  });
});

describe('summaryQuerySchema', () => {
  it('accepts from/to and nothing else meaningful', () => {
    expect(summaryQuerySchema.parse({ from: '2026-01-01', to: '2026-12-31' })).toEqual({
      from: '2026-01-01',
      to: '2026-12-31',
    });
  });

  it('accepts an empty query (the farmer’s own data range is then used)', () => {
    expect(summaryQuerySchema.parse({})).toEqual({});
  });

  it('rejects a malformed bound', () => {
    expect(summaryQuerySchema.safeParse({ to: 'soon' }).success).toBe(false);
  });
});

describe('idParamSchema', () => {
  it('accepts a uuid', () => {
    expect(idParamSchema.safeParse({ id: '9f8e7d6c-1234-4abc-9def-000000000000' }).success).toBe(true);
  });

  it('rejects a non-uuid id', () => {
    expect(idParamSchema.safeParse({ id: '123' }).success).toBe(false);
  });
});
