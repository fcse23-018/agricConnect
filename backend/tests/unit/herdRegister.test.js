import { describe, it, expect } from '@jest/globals';

import {
  HERD_EVENT_TYPES,
  createHerdEventSchema,
  updateHerdEventSchema,
  listHerdEventQuerySchema,
} from '../../src/modules/farm/records.schemas.js';
import { ENTITIES } from '../../src/modules/sync/sync.entities.js';
import { toHerdSummary } from '../../src/utils/serialize.js';

/**
 * The herd register (report CR1): births, deaths, weights, sales and dipping /
 * veterinary dates, with automatic totals. These tests pin the API contract the
 * offline-first Android register depends on: the event kinds, the client_id
 * idempotency key, and the nullable money/weight columns a dipping date does
 * not carry.
 */

const base = { animalType: 'Cattle', eventType: 'BIRTH', eventDate: '2026-03-01' };

describe('createHerdEventSchema', () => {
  it('accepts every event kind the field study named', () => {
    for (const eventType of ['BIRTH', 'DEATH', 'SALE', 'WEIGHING', 'DIPPING', 'VETERINARY', 'OTHER']) {
      expect(createHerdEventSchema.parse({ ...base, eventType }).eventType).toBe(eventType);
    }
    expect(HERD_EVENT_TYPES).toEqual(
      ['BIRTH', 'DEATH', 'SALE', 'WEIGHING', 'DIPPING', 'VETERINARY', 'OTHER'],
    );
  });

  it('defaults headCount to a single animal', () => {
    expect(createHerdEventSchema.parse(base).headCount).toBe(1);
  });

  it('accepts a multi-head entry with money and a tag', () => {
    const parsed = createHerdEventSchema.parse({
      ...base,
      eventType: 'SALE',
      headCount: 3,
      unitPrice: 4500,
      amount: 13500,
      tagNumber: 'BW0123',
      notes: 'Sold at Ramotswa auction',
      clientId: 'herd-42',
    });
    expect(parsed.headCount).toBe(3);
    expect(parsed.amount).toBe(13500);
    expect(parsed.clientId).toBe('herd-42');
  });

  it('accepts a dipping date with neither weight nor money', () => {
    const parsed = createHerdEventSchema.parse({
      ...base,
      eventType: 'DIPPING',
      weightKg: null,
      unitPrice: null,
      amount: null,
    });
    expect(parsed.weightKg).toBeNull();
    expect(parsed.unitPrice).toBeNull();
    expect(parsed.amount).toBeNull();
  });

  it('rejects a zero/negative head count with a field-level error', () => {
    // Totals SUM(head_count); a zero birth would silently vanish, a negative
    // one would subtract from the herd.
    const result = createHerdEventSchema.safeParse({ ...base, headCount: 0 });
    expect(result.success).toBe(false);
    expect(result.error.issues.map((issue) => issue.path.join('.'))).toContain('headCount');
  });

  it('rejects a made-up event kind', () => {
    expect(createHerdEventSchema.safeParse({ ...base, eventType: 'RODEO' }).success).toBe(false);
  });

  it('rejects a negative weight with a field-level error', () => {
    const result = createHerdEventSchema.safeParse({ ...base, weightKg: -5 });
    expect(result.success).toBe(false);
    expect(result.error.issues.map((issue) => issue.path.join('.'))).toContain('weightKg');
  });

  it('requires a YYYY-MM-DD date', () => {
    expect(createHerdEventSchema.safeParse({ ...base, eventDate: '01-03-2026' }).success).toBe(false);
  });
});

describe('updateHerdEventSchema', () => {
  it('accepts a partial update', () => {
    expect(updateHerdEventSchema.parse({ headCount: 2 }).headCount).toBe(2);
  });

  it('refuses an empty update', () => {
    expect(updateHerdEventSchema.safeParse({}).success).toBe(false);
  });
});

describe('listHerdEventQuerySchema', () => {
  it('filters by date range, animal type and event kind', () => {
    const parsed = listHerdEventQuerySchema.parse({
      from: '2026-01-01',
      to: '2026-12-31',
      animalType: 'Goats',
      eventType: 'DIPPING',
      page: 2,
      pageSize: 50,
    });
    expect(parsed.animalType).toBe('Goats');
    expect(parsed.eventType).toBe('DIPPING');
    expect(parsed.page).toBe(2);
  });

  it('caps pageSize at 100', () => {
    expect(listHerdEventQuerySchema.safeParse({ pageSize: 101 }).success).toBe(false);
  });
});

describe('the HerdEvent sync entity', () => {
  const payload = {
    clientId: 'herd-42',
    animalType: 'Cattle',
    eventType: 'BIRTH',
    eventDate: '2026-03-01',
    headCount: 1,
    notes: 'Heifer calf',
  };

  it('is registered with the same roles as livestock counts', () => {
    expect(ENTITIES.HerdEvent.table).toBe('herd_events');
    expect(ENTITIES.HerdEvent.clientIdBased).toBe(true);
    expect(ENTITIES.HerdEvent.softDelete).toBe(true);
  });

  it('validates like the REST schema, including a calendar-date coercion', () => {
    const parsed = ENTITIES.HerdEvent.validate({ ...payload, eventDate: '2026-03-01T00:00:00.000Z' });
    expect(parsed.eventDate).toBe('2026-03-01');
  });

  it('binds every field into the INSERT', () => {
    const parsed = ENTITIES.HerdEvent.validate({
      ...payload,
      eventType: 'SALE',
      weightKg: 400,
      unitPrice: 4500,
      amount: 4500,
      tagNumber: 'BW0123',
    });
    const { sql, params } = ENTITIES.HerdEvent.buildInsert({
      payload: parsed,
      ownerFarmerId: 'farmer-uuid',
      userId: 'actor-uuid',
      clientId: 'herd-42',
    });

    expect(sql).toContain('INSERT INTO herd_events');
    expect(params).toHaveLength(12);
    expect(params[0]).toBe('farmer-uuid');
    expect(params[1]).toBe('Cattle');
    expect(params[2]).toBe('SALE');
    expect(params[4]).toBe(1);
    expect(params[11]).toBe('actor-uuid');
  });

  it('serializes a stored row back to the client shape', () => {
    const serialized = ENTITIES.HerdEvent.serialize({
      id: 'event-uuid',
      client_id: 'herd-42',
      farmer_id: 'farmer-uuid',
      animal_type: 'Cattle',
      event_type: 'DIPPING',
      event_date: '2026-03-01',
      head_count: 12,
      weight_kg: null,
      unit_price: null,
      amount: null,
      tag_number: null,
      notes: 'Seasonal dip',
      created_at: '2026-03-01T00:00:00.000Z',
      updated_at: '2026-03-01T00:00:00.000Z',
    });
    expect(serialized).toEqual(
      expect.objectContaining({
        eventId: 'event-uuid',
        eventType: 'DIPPING',
        headCount: 12,
        weightKg: null,
        notes: 'Seasonal dip',
      }),
    );
  });
});

describe('toHerdSummary', () => {
  it('serializes a summary-view row with totals', () => {
    const serialized = toHerdSummary({
      farmer_id: 'farmer-uuid',
      animal_type: 'Cattle',
      births: 3,
      deaths: 1,
      head_sold: 2,
      sale_proceeds: '9000.00',
      weighing_entries: 4,
      last_weighed_on: '2026-05-01',
      last_dipped_on: '2026-04-15',
      last_veterinary_on: null,
      last_event_on: '2026-05-01',
      entries: 11,
    });
    expect(serialized.births).toBe(3);
    expect(serialized.deaths).toBe(1);
    expect(serialized.headSold).toBe(2);
    expect(serialized.saleProceeds).toBe(9000);
    expect(serialized.lastDippedOn).toBe('2026-04-15');
    expect(serialized.lastVeterinaryOn).toBeNull();
  });

  it('defaults missing totals to zero rather than null', () => {
    // The client must be able to SUM these without null handling.
    const serialized = toHerdSummary({
      farmer_id: 'farmer-uuid',
      animal_type: 'Goats',
    });
    expect(serialized.births).toBe(0);
    expect(serialized.deaths).toBe(0);
    expect(serialized.headSold).toBe(0);
    expect(serialized.saleProceeds).toBe(0);
    expect(serialized.entries).toBe(0);
  });
});
