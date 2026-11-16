import { describe, it, expect } from '@jest/globals';

import {
  createListingSchema,
  updateListingSchema,
} from '../../src/modules/marketplace/marketplace.schemas.js';
import { ENTITIES } from '../../src/modules/sync/sync.entities.js';
import { toListing } from '../../src/utils/serialize.js';

/**
 * Per-animal listing attributes (report CR2: class, age, weight, price per
 * animal, tag number — the photo column already existed).
 *
 * The defect behind these tests: a keeper had to list an animal as a price per
 * kilogram, so "P150,000 per kilogram for 150 kg" read as P22.5 million (DEF-2,
 * already fixed by the unit list) and nothing on the listing said *which*
 * animal it was. The fields are nullable so produce listings are unaffected.
 */

/** A valid listing apart from the fields under test. */
const base = { produce: 'Cattle', quantity: 1, unit: 'head', price: 4500 };

describe('createListingSchema - per-animal attributes', () => {
  it('accepts all four attributes on a livestock listing', () => {
    const parsed = createListingSchema.parse({
      ...base,
      animalClass: 'Bonsmara heifer',
      ageMonths: 30,
      weightKg: 420.5,
      tagNumber: 'BW0123',
    });
    expect(parsed).toMatchObject({
      animalClass: 'Bonsmara heifer',
      ageMonths: 30,
      weightKg: 420.5,
      tagNumber: 'BW0123',
    });
  });

  it('keeps every attribute optional for a produce listing', () => {
    const parsed = createListingSchema.parse({ ...base, unit: 'kg' });
    expect(parsed.animalClass ?? null).toBeNull();
    expect(parsed.ageMonths ?? null).toBeNull();
    expect(parsed.weightKg ?? null).toBeNull();
    expect(parsed.tagNumber ?? null).toBeNull();
  });

  it('normalises an emptied string to null so a form can clear a field', () => {
    // DEF-3: saving a cleared field must not become an error the farmer cannot
    // see the cause of.
    const parsed = createListingSchema.parse({
      ...base,
      animalClass: '   ',
      tagNumber: '',
    });
    expect(parsed.animalClass).toBeNull();
    expect(parsed.tagNumber).toBeNull();
  });

  it('rejects a nonsensical age with a field-level error (DEF-3)', () => {
    const result = createListingSchema.safeParse({ ...base, ageMonths: 750 });
    expect(result.success).toBe(false);
    // The error must name the field, not just say "check your entries".
    const paths = result.error.issues.map((issue) => issue.path.join('.'));
    expect(paths).toContain('ageMonths');
  });

  it('rejects a negative weight with a field-level error (DEF-3)', () => {
    const result = createListingSchema.safeParse({ ...base, weightKg: -1 });
    expect(result.success).toBe(false);
    const paths = result.error.issues.map((issue) => issue.path.join('.'));
    expect(paths).toContain('weightKg');
  });

  it('rejects an over-long tag number, matching the DB constraint', () => {
    expect(createListingSchema.safeParse({ ...base, tagNumber: 'x'.repeat(61) }).success).toBe(false);
  });

  it('coerces numeric strings the way the rest of the API does', () => {
    const parsed = createListingSchema.parse({
      ...base,
      ageMonths: '42',
      weightKg: '300',
    });
    expect(parsed.ageMonths).toBe(42);
    expect(parsed.weightKg).toBe(300);
  });
});

describe('updateListingSchema - per-animal attributes', () => {
  it('accepts a partial update of the attributes', () => {
    const parsed = updateListingSchema.parse({ ageMonths: 12 });
    expect(parsed.ageMonths).toBe(12);
  });

  it('accepts clearing an attribute back to null', () => {
    const parsed = updateListingSchema.parse({ animalClass: null });
    expect(parsed.animalClass).toBeNull();
  });

  it('still refuses a smuggled status change', () => {
    const result = updateListingSchema.safeParse({ status: 'PUBLISHED' });
    expect(result.success).toBe(false);
    expect(result.error.issues.some((issue) => issue.path.includes('status'))).toBe(true);
  });
});

describe('the sync entity carries the same attributes', () => {
  const payload = {
    produce: 'Cattle',
    quantity: 1,
    unit: 'head',
    price: 4500,
    animalClass: 'Bonsmara heifer',
    ageMonths: 30,
    weightKg: 420.5,
    tagNumber: 'BW0123',
  };

  it('validates them on the offline path exactly like the REST path', () => {
    const parsed = ENTITIES.ProduceListing.validate(payload);
    expect(parsed.animalClass).toBe('Bonsmara heifer');
    expect(parsed.tagNumber).toBe('BW0123');
  });

  it('normalises emptied strings to null on the offline path too', () => {
    const parsed = ENTITIES.ProduceListing.validate({ ...payload, tagNumber: '' });
    expect(parsed.tagNumber).toBeNull();
  });

  it('binds every attribute into the INSERT', () => {
    const parsed = ENTITIES.ProduceListing.validate(payload);
    const { sql, params } = ENTITIES.ProduceListing.buildInsert({
      payload: parsed,
      ownerFarmerId: 'farmer-uuid',
      id: 'listing-uuid',
    });

    expect(sql).toContain('animal_class, age_months, weight_kg, tag_number');
    expect(params).toHaveLength(13);
    expect(params[9]).toBe('Bonsmara heifer');
    expect(params[10]).toBe(30);
    expect(params[11]).toBe(420.5);
    expect(params[12]).toBe('BW0123');
  });

  it('binds every attribute into the UPDATE', () => {
    const parsed = ENTITIES.ProduceListing.validate(payload);
    const { sql, params } = ENTITIES.ProduceListing.buildUpdate({
      payload: parsed,
      existingId: 'existing-uuid',
    });

    expect(sql).toContain('animal_class = $8');
    expect(params[7]).toBe('Bonsmara heifer');
    expect(params[8]).toBe(30);
    expect(params[9]).toBe(420.5);
    expect(params[10]).toBe('BW0123');
    expect(params[11]).toBe('existing-uuid');
  });

  it('writes NULL for a produce listing with none of the attributes', () => {
    const parsed = ENTITIES.ProduceListing.validate({
      produce: 'Maize meal',
      quantity: 50,
      unit: 'bag',
      price: 120,
    });
    const { params } = ENTITIES.ProduceListing.buildInsert({
      payload: parsed,
      ownerFarmerId: 'farmer-uuid',
      id: 'listing-uuid',
    });
    expect(params[9]).toBeNull();
    expect(params[10]).toBeNull();
    expect(params[11]).toBeNull();
    expect(params[12]).toBeNull();
  });
});

describe('toListing round-trips the attributes', () => {
  it('serialises database rows back to the client shape', () => {
    const serialized = toListing({
      id: 'listing-uuid',
      farmer_id: 'farmer-uuid',
      produce: 'Cattle',
      description: null,
      quantity: '1.00',
      reserved_qty: '0.00',
      sold_qty: '0.00',
      unit: 'head',
      price: '4500.00',
      currency: 'BWP',
      status: 'DRAFT',
      image_url: null,
      location_text: null,
      animal_class: 'Bonsmara heifer',
      age_months: 30,
      weight_kg: '420.50',
      tag_number: 'BW0123',
      published_at: null,
      created_at: '2026-01-01T00:00:00.000Z',
      updated_at: '2026-01-01T00:00:00.000Z',
    });

    expect(serialized.animalClass).toBe('Bonsmara heifer');
    expect(serialized.ageMonths).toBe(30);
    expect(serialized.weightKg).toBe(420.5);
    expect(serialized.tagNumber).toBe('BW0123');
  });

  it('emits null (never undefined) for a produce listing', () => {
    // An undefined key would silently vanish from JSON and break clients that
    // read the field. Null is an explicit "not an animal".
    const serialized = toListing({
      id: 'listing-uuid',
      farmer_id: 'farmer-uuid',
      produce: 'Maize meal',
      quantity: '50.00',
      reserved_qty: '0.00',
      sold_qty: '0.00',
      unit: 'bag',
      price: '120.00',
      currency: 'BWP',
      status: 'DRAFT',
      created_at: '2026-01-01T00:00:00.000Z',
      updated_at: '2026-01-01T00:00:00.000Z',
    });

    expect(Object.prototype.hasOwnProperty.call(serialized, 'animalClass')).toBe(true);
    expect(serialized.animalClass).toBeNull();
    expect(serialized.ageMonths).toBeNull();
    expect(serialized.weightKg).toBeNull();
    expect(serialized.tagNumber).toBeNull();
  });
});
