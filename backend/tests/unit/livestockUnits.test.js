import { describe, it, expect } from '@jest/globals';

import {
  LISTING_UNITS,
  LIVESTOCK_UNITS,
  createListingSchema,
  updateListingSchema,
} from '../../src/modules/marketplace/marketplace.schemas.js';
import { ENTITIES } from '../../src/modules/sync/sync.entities.js';

/**
 * Livestock units.
 *
 * Farmers sell animals two ways: by the animal ("20 head of cattle") and as one
 * lot ("the herd"). The API previously accepted neither the group words nor the
 * idea behind them, so a farmer selling a herd was rejected. These tests pin the
 * contract:
 *
 *  - the group units exist and validate on create and update;
 *  - the DB, the REST schema and the sync schema accept the *same* list — the sync
 *    entity used to keep its own private copy, which is exactly how a value the
 *    API accepts gets silently rejected on the offline path;
 *  - the group units are still just units: they do not smuggle in free text, so
 *    an arbitrary word like "cattle" is still refused with a clear error.
 */

describe('LISTING_UNITS', () => {
  it('accepts the livestock group units', () => {
    expect(LISTING_UNITS).toContain('head');
    expect(LISTING_UNITS).toContain('herd');
    expect(LISTING_UNITS).toContain('flock');
  });

  it('keeps every established produce unit', () => {
    // Adding livestock must not remove what already worked.
    for (const unit of ['kg', 'tonne', 'bag', 'crate', 'bunch', 'litre']) {
      expect(LISTING_UNITS).toContain(unit);
    }
  });

  it('marks herd and flock as livestock alongside head', () => {
    expect(LIVESTOCK_UNITS).toEqual(expect.arrayContaining(['head', 'herd', 'flock']));
  });

  it('has no duplicates', () => {
    expect(new Set(LISTING_UNITS).size).toBe(LISTING_UNITS.length);
  });
});

describe('createListingSchema units', () => {
  /** A listing that is valid apart from the unit under test. */
  const base = { produce: 'Cattle', quantity: 1, price: 5000, unit: 'kg' };

  it.each(['head', 'herd', 'flock'])('accepts the unit %s', (unit) => {
    const parsed = createListingSchema.parse({ ...base, unit });
    expect(parsed.unit).toBe(unit);
  });

  it('still refuses a free-text unit with a validation error', () => {
    // "cattle" is a species, not a unit. The picker should prevent this, but the
    // server must not accept it either — a free-text unit cannot be compared.
    const result = createListingSchema.safeParse({ ...base, unit: 'cattle' });
    expect(result.success).toBe(false);
  });

  it('defaults to kg when no unit is given', () => {
    const { unit, ...withoutUnit } = base;
    expect(createListingSchema.parse(withoutUnit).unit).toBe('kg');
  });

  it('accepts a whole-group listing as quantity 1', () => {
    // The real shape of "sell the herd": one lot, one price.
    const parsed = createListingSchema.parse({ produce: 'Goats', quantity: 1, price: 12000, unit: 'herd' });
    expect(parsed.unit).toBe('herd');
    expect(parsed.quantity).toBe(1);
  });
});

describe('updateListingSchema units', () => {
  it('accepts changing a listing to a livestock group unit', () => {
    expect(updateListingSchema.parse({ unit: 'flock' }).unit).toBe('flock');
  });

  it('refuses a free-text unit on update too', () => {
    expect(updateListingSchema.safeParse({ unit: 'cows' }).success).toBe(false);
  });
});

describe('the sync entity reuses the same unit list', () => {
  it('accepts every unit the REST API accepts', () => {
    const listing = ENTITIES.ProduceListing;
    expect(listing).toBeDefined();

    // The sync path must not have a private, narrower copy of the enum — that is
    // how a listing valid on the API becomes invalid when it is pushed offline.
    for (const unit of LISTING_UNITS) {
      const parsed = listing.validate({
        produce: 'Maize',
        quantity: 1,
        price: 10,
        unit,
      });
      expect(parsed.unit).toBe(unit);
    }
  });

  it('refuses a free-text unit on the sync path as well', () => {
    expect(() =>
      ENTITIES.ProduceListing.validate({ produce: 'Maize', quantity: 1, price: 10, unit: 'cattle' }),
    ).toThrow();
  });
});
