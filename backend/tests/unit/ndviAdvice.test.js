import { describe, it, expect } from '@jest/globals';

import {
  buildNdviAdvice,
  detectTrend,
  TREND_NOISE,
  TrendDirection,
  AdvicePriority,
  STRESS_BANDS,
  NDVI_SCALE_GUIDE,
  NDVI_LIMITS,
} from '../../src/services/ndviAdvice.js';
import { StressLevel } from '../../src/services/ndvi.js';
import {
  presentNdvi,
  adviceForNdvi,
  NDVI_DISCLAIMER,
} from '../../src/modules/satellite/satellite.service.js';

/** A fixed clock keeps the "how old is this reading" assertions deterministic. */
const TODAY = new Date('2026-06-20T00:00:00Z');

describe('buildNdviAdvice — refuses to advise without a usable value', () => {
  it('returns null when there is no value, so no advice is shown for FAILED rows', () => {
    expect(buildNdviAdvice({ meanNdviValue: null })).toBeNull();
    expect(buildNdviAdvice({ meanNdviValue: undefined })).toBeNull();
    expect(buildNdviAdvice({ meanNdviValue: Number.NaN })).toBeNull();
    expect(buildNdviAdvice({})).toBeNull();
    expect(buildNdviAdvice()).toBeNull();
  });

  it('never lets a non-numeric string become a measurement', () => {
    expect(buildNdviAdvice({ meanNdviValue: 'healthy' })).toBeNull();
  });
});

describe('buildNdviAdvice — the number it reports is the number it was given', () => {
  it('echoes the measured value and never rounds it into a different one', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.4321, source: 'SENTINEL-2', today: TODAY });
    expect(advice.assessment.value).toBe(0.4321);
  });

  it('accepts PostgreSQL numeric strings, which is how the driver returns numeric columns', () => {
    const advice = buildNdviAdvice({ meanNdviValue: '0.55', today: TODAY });
    expect(advice.assessment.value).toBe(0.55);
  });

  it('derives the band from the value when the caller supplies no stress level', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.1, today: TODAY }).assessment.stressLevel)
      .toBe(StressLevel.HIGH_STRESS);
    expect(buildNdviAdvice({ meanNdviValue: 0.3, today: TODAY }).assessment.stressLevel)
      .toBe(StressLevel.MODERATE_STRESS);
    expect(buildNdviAdvice({ meanNdviValue: 0.7, today: TODAY }).assessment.stressLevel)
      .toBe(StressLevel.HEALTHY_VEGETATION);
  });

  it('respects an explicit stress level rather than recomputing it', () => {
    const advice = buildNdviAdvice({
      meanNdviValue: 0.1,
      stressLevel: StressLevel.HEALTHY_VEGETATION,
      today: TODAY,
    });
    expect(advice.assessment.stressLevel).toBe(StressLevel.HEALTHY_VEGETATION);
  });
});

describe('buildNdviAdvice — urgent, cautious and steady bands say different things', () => {
  it('high stress is an act-now instruction with concrete checks', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.12, today: TODAY });
    expect(advice.priority).toBe(AdvicePriority.NOW);
    expect(advice.assessment.headline).toMatch(/act now/i);
    // The cause is unknown, so the advice must be investigative, not prescriptive.
    expect(advice.actions.map((a) => a.title).join(' ')).toMatch(/walk the plot/i);
    expect(advice.actions.map((a) => a.title).join(' ')).toMatch(/soil moisture/i);
    expect(advice.watchFor.length).toBeGreaterThan(0);
  });

  it('moderate stress is a watch-closely instruction', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.3, today: TODAY });
    expect(advice.priority).toBe(AdvicePriority.SOON);
    expect(advice.assessment.headline).toMatch(/watch closely/i);
  });

  it('healthy vegetation is a keep-going instruction', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.72, today: TODAY });
    expect(advice.priority).toBe(AdvicePriority.ROUTINE);
    expect(advice.assessment.headline).toMatch(/on track/i);
  });

  it('always ends with the retest cadence and the record-what-you-find step', () => {
    for (const value of [0.12, 0.3, 0.72]) {
      const titles = buildNdviAdvice({ meanNdviValue: value, today: TODAY }).actions.map((a) => a.title);
      expect(titles.join(' ')).toMatch(/re-run the index/i);
      expect(titles.join(' ')).toMatch(/record what you actually find/i);
    }
  });

  it('gives every action a title and a detail, so the client never renders a bare bullet', () => {
    for (const value of [0.12, 0.3, 0.72]) {
      for (const action of buildNdviAdvice({ meanNdviValue: value, today: TODAY }).actions) {
        expect(typeof action.title).toBe('string');
        expect(action.title.length).toBeGreaterThan(0);
        expect(typeof action.detail).toBe('string');
        expect(action.detail.length).toBeGreaterThan(0);
      }
    }
  });

  it('never claims to know the cause of a poor reading', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.05, today: TODAY });
    const text = [advice.assessment.meaning, ...advice.actions.flatMap((a) => [a.title, a.detail])].join(' ');
    // It may *mention* causes as possibilities; it must not assert one.
    expect(text).not.toMatch(/\byour crop has\b/i);
    expect(advice.limits.join(' ')).toMatch(/cannot name the cause/i);
  });
});

describe('detectTrend — only a movement larger than the noise floor is a trend', () => {
  it('reports a first reading when there is nothing to compare with', () => {
    expect(detectTrend(0.5, null).direction).toBe(TrendDirection.FIRST_READING);
    expect(detectTrend(0.5, undefined).previousValue).toBeNull();
  });

  it('treats sub-threshold movement as stable', () => {
    expect(detectTrend(0.5, 0.5 - TREND_NOISE / 2).direction).toBe(TrendDirection.STABLE);
    expect(detectTrend(0.5, 0.5).direction).toBe(TrendDirection.STABLE);
  });

  it('treats the threshold itself as a real change', () => {
    expect(detectTrend(0.6, 0.6 - TREND_NOISE).direction).toBe(TrendDirection.IMPROVING);
    expect(detectTrend(0.4, 0.4 + TREND_NOISE).direction).toBe(TrendDirection.DECLINING);
  });

  it('reports the delta to four places', () => {
    expect(detectTrend(0.62, 0.5).delta).toBe(0.12);
    expect(detectTrend(0.5, 0.62).delta).toBe(-0.12);
  });

  it('is exposed on the advice block with the previous value and date', () => {
    const advice = buildNdviAdvice({
      meanNdviValue: 0.62,
      previous: { value: 0.5, date: '2026-06-05' },
      today: TODAY,
    });
    expect(advice.trend.direction).toBe(TrendDirection.IMPROVING);
    expect(advice.trend.delta).toBe(0.12);
    expect(advice.trend.previousValue).toBe(0.5);
    expect(advice.trend.previousDate).toBe('2026-06-05');
    expect(advice.assessment.summary).toMatch(/recovering/i);
  });

  it('tells the farmer this is the first reading when there is no history', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.62, today: TODAY });
    expect(advice.trend.direction).toBe(TrendDirection.FIRST_READING);
    expect(advice.trend.note).toMatch(/first reading/i);
    expect(advice.assessment.summary).toMatch(/first reading for this plot/i);
  });

  it('flags a decline without implying a cause', () => {
    const advice = buildNdviAdvice({
      meanNdviValue: 0.3,
      previous: { value: 0.5, date: '2026-06-05' },
      today: TODAY,
    });
    expect(advice.trend.direction).toBe(TrendDirection.DECLINING);
    expect(advice.assessment.summary).toMatch(/keep watching/i);
  });
});

describe('buildNdviAdvice — confidence is about the measurement, never about the crop', () => {
  it('labels a simulated reading LOW and says plainly that it is a placeholder', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, source: 'MOCK', cloudCover: 10, today: TODAY });
    expect(advice.confidence.level).toBe('LOW');
    expect(advice.confidence.note).toMatch(/demonstration/i);
    expect(advice.confidence.note).toMatch(/placeholder/i);
    expect(advice.context.sourceIsSimulated).toBe(true);
  });

  it('marks a simulated reading however the source is cased', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.5, source: 'mock', today: TODAY }).context.sourceIsSimulated).toBe(true);
  });

  it('treats heavy cloud as LOW even for real imagery', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, source: 'SENTINEL-2', cloudCover: 65, today: TODAY });
    expect(advice.confidence.level).toBe('LOW');
    expect(advice.confidence.note).toMatch(/Cloud cover was 65%/);
    expect(advice.context.sourceIsSimulated).toBe(false);
  });

  it('treats a clear real observation as HIGH', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, source: 'SENTINEL-2', cloudCover: 8, today: TODAY });
    expect(advice.confidence.level).toBe('HIGH');
    expect(advice.context.cloudCoverPercent).toBe(8);
  });

  it('treats an unqualified observation as MEDIUM', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.5, source: 'SENTINEL-2', cloudCover: null, today: TODAY })
      .confidence.level).toBe('MEDIUM');
    expect(buildNdviAdvice({ meanNdviValue: 0.5, source: 'SENTINEL-2', cloudCover: 35, today: TODAY })
      .confidence.level).toBe('MEDIUM');
  });
});

describe('buildNdviAdvice — context the farmer can check the reading against', () => {
  it('reports how old the observation is, never negative', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: '2026-06-15', today: TODAY })
      .context.daysSinceObservation).toBe(5);
    expect(buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: '2026-06-20', today: TODAY })
      .context.daysSinceObservation).toBe(0);
    // A provider date in the future must not produce a negative age.
    expect(buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: '2026-07-01', today: TODAY })
      .context.daysSinceObservation).toBe(0);
  });

  it('is null-safe when the observation date is missing or malformed', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.5, today: TODAY }).context.daysSinceObservation).toBeNull();
    expect(buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: 'not-a-date', today: TODAY })
      .context.daysSinceObservation).toBeNull();
  });

  it('carries the plot name, the area the average covers and the source through', () => {
    const advice = buildNdviAdvice({
      meanNdviValue: 0.5,
      plotName: 'North field',
      areaHectares: 1.1263,
      satelliteDate: '2026-06-15',
      cloudCover: 12,
      source: 'SENTINEL-2',
      today: TODAY,
    });
    expect(advice.context.plotName).toBe('North field');
    expect(advice.context.areaHectares).toBe(1.1263);
    expect(advice.context.observedOn).toBe('2026-06-15');
    expect(advice.context.source).toBe('SENTINEL-2');
    expect(advice.assessment.summary).toMatch(/North field/);
    expect(advice.assessment.summary).toMatch(/0\.50/);
  });

  it('mentions the age of the observation in the summary', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: '2026-06-15', today: TODAY });
    expect(advice.assessment.summary).toMatch(/5 days old/i);
  });
});

describe('buildNdviAdvice — the reading is put in the farmer\'s hands, with its limits', () => {
  it('ships the reference scale so the number can be read without trusting a colour', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, today: TODAY });
    expect(advice.scaleGuide.length).toBe(NDVI_SCALE_GUIDE.length);
    expect(advice.scaleGuide[0].range).toBeDefined();
    expect(advice.scaleGuide[0].meaning).toBeDefined();
  });

  it('ships the limits of what a vegetation index can tell you', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, today: TODAY });
    expect(advice.limits.length).toBe(NDVI_LIMITS.length);
    expect(advice.limits.join(' ')).toMatch(/average over the whole plot/i);
  });

  it('carries a disclaimer that this is general guidance, not a prescription', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.5, today: TODAY }).disclaimer)
      .toMatch(/not a site-specific agronomic recommendation/i);
  });

  it('reports a band range only when it belongs to the reading\'s own band', () => {
    expect(buildNdviAdvice({ meanNdviValue: 0.1, today: TODAY }).assessment.band)
      .toEqual(STRESS_BANDS[StressLevel.HIGH_STRESS]);
    expect(buildNdviAdvice({ meanNdviValue: 0.6, today: TODAY }).assessment.band)
      .toEqual(STRESS_BANDS[StressLevel.HEALTHY_VEGETATION]);
  });

  it('returns a plain JSON-safe structure — no Dates, no undefined', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.5, satelliteDate: '2026-06-15', today: TODAY });
    const roundTripped = JSON.parse(JSON.stringify(advice));
    expect(roundTripped.assessment.value).toBe(0.5);
    expect(roundTripped.context.daysSinceObservation).toBe(5);
    expect(JSON.stringify(advice)).not.toMatch(/undefined/);
  });
});

/**
 * The advice is only useful if it actually reaches the wire. These pin the two
 * functions the satellite routes use, including the guard that keeps a failed or
 * in-flight record from ever being dressed up with guidance.
 */
describe('presentNdvi — every response carries the disclaimer, advice only where it exists', () => {
  const row = {
    id: 'ndvi-1',
    plot_id: 'plot-1',
    farmer_id: 'user-1',
    satellite_date: '2026-06-15',
    mean_ndvi_value: '0.42',
    stress_level: null,
    status: 'GENERATED',
    failure_reason: null,
    cloud_cover: '12.00',
    compressed_vector_json: null,
    is_cached: false,
    source: 'SENTINEL-2',
    created_at: '2026-06-16T08:00:00.000Z',
  };

  it('serialises the row, normalises the numeric strings, and says how to read it', () => {
    const body = presentNdvi(row);

    expect(body.meanNdviValue).toBe(0.42);
    expect(body.cloudCover).toBe(12);
    expect(body.disclaimer).toBe(NDVI_DISCLAIMER);
    // Present-but-null, so the client can branch without a key check.
    expect(body.advice).toBeNull();
  });

  it('attaches the advice it was handed, unchanged', () => {
    const advice = buildNdviAdvice({ meanNdviValue: 0.42, today: TODAY });
    expect(presentNdvi(row, advice).advice).toBe(advice);
  });

  it('has an explicit `advice` key so an older client cannot mistake absence for guidance', () => {
    expect(Object.keys(presentNdvi(row))).toContain('advice');
  });
});

describe('adviceForNdvi — refuses to advise where there is no generated reading', () => {
  it.each([
    ['no row at all', null],
    ['an undefined row', undefined],
    ['a failed computation', { status: 'FAILED', mean_ndvi_value: 0.5 }],
    ['a request still in flight', { status: 'REQUESTED', mean_ndvi_value: null }],
    ['a generated row with no value', { status: 'GENERATED', mean_ndvi_value: null }],
  ])('returns null for %s, without querying the database', async (_label, row) => {
    await expect(adviceForNdvi(row)).resolves.toBeNull();
  });
});
