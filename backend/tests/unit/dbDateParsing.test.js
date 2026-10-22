import { describe, it, expect } from '@jest/globals';

/**
 * Regression tests for the bug that made the Satellite screen fail in production
 * with "we couldn't read the server response".
 *
 * The symptom, observed live against a real plot:
 *
 *   GET /satellite/overview  ->  200, but
 *     observations           : 0      (a series of 23 stored rows)
 *     summary.available      : false  "No valid NDVI reading is available"
 *     newestObservation      : "Thu Oct 22"
 *     note                   : "...is Thu Oct 22, NaN day(s) old."
 *
 *   GET /satellite/plots/latest -> the same plot DID have an observation,
 *     observedOn: "Thu Oct 22", ndvi: -0.1, source: "MOCK"
 *
 * Two endpoints disagreed about the same plot, and the date was a day-of-week
 * string. The cause was not in the satellite code at all: node-postgres parses a
 * `date` column into a JavaScript `Date` at *local* midnight, and the callers
 * did `String(row.observed_on).slice(0, 10)`, which yields `"Thu Oct 22"`.
 *
 * That string then failed the analysis engine's strict `/^\d{4}-\d{2}-\d{2}/`
 * check, so EVERY row was dropped and a plot with a full series looked empty.
 *
 * A `date` is a calendar date with no time and no zone. Parsing it at local
 * midnight invents both — and on a UTC server it can shift the day, silently
 * ageing an observation by one day. These tests pin the three defences:
 *   1. the `pg` type parser returns `date` as a plain `YYYY-MM-DD` string;
 *   2. the analysis engine normalises a `Date` instead of discarding the row;
 *   3. `dateOnly` produces a real date for both strings and `Date` objects.
 */

const DATE_OID = 1082;

describe('pg date type parser (the root cause)', () => {
  // Importing the db module is what REGISTERS the parser; without it,
  // `getTypeParser` returns pg's default (a Date at local midnight). This import
  // is deliberately at the top of the first test so the ordering is explicit.
  const loadParser = async () => {
    await import('../../src/db/index.js');
    const pg = (await import('pg')).default;
    return pg.types.getTypeParser(DATE_OID);
  };

  it('returns a Postgres `date` as a plain YYYY-MM-DD string', async () => {
    const parser = await loadParser();

    expect(parser('2026-10-22')).toBe('2026-10-22');
    expect(typeof parser('2026-10-22')).toBe('string');
  });

  it('never yields the day-of-week form that broke the series', async () => {
    const parser = await loadParser();

    const value = parser('2026-10-22');
    // The old behaviour: String(new Date(...)).slice(0, 10) === 'Thu Oct 22'
    expect(value).not.toMatch(/^[A-Z][a-z]{2} /);
    expect(value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('keeps the calendar date stable regardless of the machine timezone', async () => {
    const parser = await loadParser();

    // The dangerous half of the old bug: a Date at local midnight is NOT the same
    // instant as UTC midnight, so on a UTC server '2026-10-22' could render as
    // '2026-10-21'. A text passthrough cannot drift.
    const original = process.env.TZ;
    try {
      process.env.TZ = 'Africa/Gaborone'; // UTC+2
      expect(parser('2026-10-22')).toBe('2026-10-22');
      process.env.TZ = 'UTC';
      expect(parser('2026-10-22')).toBe('2026-10-22');
      process.env.TZ = 'Pacific/Kiritimati'; // UTC+14
      expect(parser('2026-10-22')).toBe('2026-10-22');
    } finally {
      if (original === undefined) delete process.env.TZ;
      else process.env.TZ = original;
    }
  });
});

describe('dateOnly serialiser', () => {
  it('passes a date-only string through unchanged', async () => {
    const { dateOnly } = await import('../../src/utils/serialize.js');
    expect(dateOnly('2026-10-22')).toBe('2026-10-22');
  });

  it('truncates an ISO timestamp to its date', async () => {
    const { dateOnly } = await import('../../src/utils/serialize.js');
    expect(dateOnly('2026-10-22T11:45:00.000Z')).toBe('2026-10-22');
  });

  it('formats a Date rather than producing a day-of-week string', async () => {
    const { dateOnly } = await import('../../src/utils/serialize.js');
    // Midday UTC so a +/-14h machine zone cannot shift the calendar day.
    const value = dateOnly(new Date('2026-10-22T12:00:00Z'));
    expect(value).toBe('2026-10-22');
    expect(value).not.toMatch(/^[A-Z][a-z]{2} /);
  });

  it('returns null for a missing value instead of "null" or NaN', async () => {
    const { dateOnly } = await import('../../src/utils/serialize.js');
    expect(dateOnly(null)).toBeNull();
    expect(dateOnly(undefined)).toBeNull();
    expect(dateOnly('')).toBeNull();
  });
});

describe('analysis engine survives a Date-shaped series', () => {
  it('normalises a Date instead of dropping the whole series', async () => {
    const { sanitiseObservations } = await import('../../src/services/satelliteAnalysis.js');

    // What node-postgres would have handed over before the parser fix.
    const rows = sanitiseObservations([
      { date: new Date('2026-10-20T12:00:00Z'), ndvi: 0.28, evi: 0.7 },
    ]);

    expect(rows).toHaveLength(1);
    expect(rows[0].date).toBe('2026-10-20');
  });

  it('drops a day-of-week string rather than inventing a date for it', async () => {
    const { sanitiseObservations, normaliseDate } = await import('../../src/services/satelliteAnalysis.js');

    expect(normaliseDate('Thu Oct 22')).toBe('');

    const rows = sanitiseObservations([{ date: 'Thu Oct 22', ndvi: 0.28 }]);
    expect(rows).toHaveLength(0);
  });

  it('would have discarded a 23-row series under the old string cast', async () => {
    const { sanitiseObservations } = await import('../../src/services/satelliteAnalysis.js');

    // Reproduce the production shape: 23 observations, each a Date at local
    // midnight. Under `String(d).slice(0,10)` every one became 'Thu Oct 22' and
    // was dropped, which is why the overview said there was no usable reading.
    const dates = [];
    for (let i = 0; i < 23; i += 1) {
      dates.push(new Date(Date.UTC(2026, 8, 1) + i * 86_400_000));
    }
    const series = sanitiseObservations(dates.map((d) => ({ date: d, ndvi: 0.3, evi: 0.5 })));

    expect(series).toHaveLength(23);
    // Every date is a real calendar date, and they stay in ascending order.
    for (const row of series) expect(row.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(series[0].date).toBe('2026-09-01');
    expect(series[22].date).toBe('2026-09-23');
  });

  it('produces an available summary from a normalised series', async () => {
    const { analyseSeries, sanitiseObservations } = await import('../../src/services/satelliteAnalysis.js');

    const observations = sanitiseObservations([
      { date: new Date('2026-10-01T12:00:00Z'), ndvi: 0.22, evi: 0.5 },
      { date: new Date('2026-10-15T12:00:00Z'), ndvi: 0.31, evi: 0.62 },
      { date: new Date('2026-10-20T12:00:00Z'), ndvi: 0.28, evi: 0.6 },
    ]);

    const analysis = analyseSeries({ observations, weather: null, now: new Date('2026-10-21T00:00:00Z') });

    // The live bug surfaced as `available: false, count: 0` on exactly this data.
    expect(analysis.summary.available).toBe(true);
    expect(analysis.summary.count).toBe(3);
    // `latest` is the whole newest observation row, so its date is what proves
    // the normalisation reached the summary.
    expect(analysis.summary.latest.date).toBe('2026-10-20');
    expect(analysis.summary.latest.ndvi).toBe(0.28);
    expect(typeof analysis.summary.dataAgeDays).toBe('number');
  });
});

describe('normaliseDate contract', () => {
  it('accepts an ISO string and returns the date part', async () => {
    const { normaliseDate } = await import('../../src/services/satelliteAnalysis.js');
    expect(normaliseDate('2026-10-22T23:59:59Z')).toBe('2026-10-22');
  });

  it('rejects non-date values without throwing', async () => {
    const { normaliseDate } = await import('../../src/services/satelliteAnalysis.js');
    expect(normaliseDate(null)).toBe('');
    expect(normaliseDate(undefined)).toBe('');
    expect(normaliseDate('')).toBe('');
    expect(normaliseDate('not a date')).toBe('');
    expect(normaliseDate(new Date('nonsense'))).toBe('');
  });

  it('is stable across timezones for a midday instant', async () => {
    const { normaliseDate } = await import('../../src/services/satelliteAnalysis.js');
    const original = process.env.TZ;
    try {
      process.env.TZ = 'Pacific/Kiritimati';
      expect(normaliseDate(new Date('2026-10-22T12:00:00Z'))).toBe('2026-10-22');
      process.env.TZ = 'Pacific/Midway';
      expect(normaliseDate(new Date('2026-10-22T12:00:00Z'))).toBe('2026-10-22');
    } finally {
      if (original === undefined) delete process.env.TZ;
      else process.env.TZ = original;
    }
  });
});

describe('staleness must not hide behind an unreadable date', () => {
  it('treats an unknown age as stale, not as fresh', async () => {
    // The colour map and the farm summary both compute freshness from the date.
    // An unparseable date used to give `NaN`, and `NaN > staleDays` is false —
    // so a plot that had never been read could report as not stale.
    const service = await import('../../src/modules/satellite/satellite.monitoring.service.js');
    const describe = service.describeIndex;

    const text = describe('ndvi', 0.3, null, 14, false);
    expect(text).toMatch(/could not be read/i);
    expect(text).not.toMatch(/from today/i);
    expect(text).not.toMatch(/NaN/);
  });

  it('still describes a real age correctly', async () => {
    const service = await import('../../src/modules/satellite/satellite.monitoring.service.js');

    expect(service.describeIndex('ndvi', 0.3, 0, 14, false)).toMatch(/from today/i);
    expect(service.describeIndex('ndvi', 0.3, 5, 14, false)).toMatch(/5 days old/i);
    expect(service.describeIndex('ndvi', 0.3, 30, 14, false)).toMatch(/history rather than a current picture/i);
  });
});
