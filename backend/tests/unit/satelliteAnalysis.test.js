import { describe, it, expect } from '@jest/globals';

import {
  sanitiseObservations,
  flagOutliers,
  smoothSeries,
  summarise,
  detectGrowthCycle,
  detectDrops,
  detectGradualDecline,
  weatherWindow,
  explainDrop,
  buildAlerts,
  analyseSeries,
  daysBetween,
  addDays,
  toEpoch,
  CLASSIFICATION,
} from '../../src/services/satelliteAnalysis.js';

/** Build a series from [date, ndvi] pairs, with EVI at 0.8 × NDVI. */
function series(pairs) {
  return pairs.map(([date, ndvi]) => ({ date, ndvi, evi: ndvi * 0.8 }));
}

/** Two-decimal rounding, so generated fixtures read like real index values. */
function round2(value) {
  return Math.round(value * 100) / 100;
}

const NOW = '2026-06-30';

describe('sanitiseObservations', () => {
  it('accepts the crop-api shape and the internal shape', () => {
    const rows = sanitiseObservations([
      { date: '2026-01-01', ndvi: 0.4, evi: 0.3, pixels: 30 },
      { observedOn: '2026-01-06', ndviValue: 0.5, eviValue: 0.4 },
    ]);
    expect(rows).toHaveLength(2);
    expect(rows[0].ndvi).toBe(0.4);
    expect(rows[1].ndvi).toBe(0.5);
  });

  it('drops rows with no usable index and sorts oldest first', () => {
    const rows = sanitiseObservations([
      { date: '2026-02-01', ndvi: 0.5 },
      { date: '2026-01-01' },
      { date: 'not-a-date', ndvi: 0.5 },
      { date: '2026-01-15', ndvi: 0.42 },
    ]);
    expect(rows.map((r) => r.date)).toEqual(['2026-01-15', '2026-02-01']);
  });

  it('collapses duplicate dates to one row', () => {
    const rows = sanitiseObservations([
      { date: '2026-01-01', ndvi: 0.4 },
      { date: '2026-01-01', ndvi: 0.45 },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].ndvi).toBe(0.45);
  });

  it('clamps impossible index values into [-1, 1]', () => {
    const rows = sanitiseObservations([{ date: '2026-01-01', ndvi: 9.5 }]);
    expect(rows[0].ndvi).toBe(1);
  });

  it('classifies quality from coverage and cloud', () => {
    const rows = sanitiseObservations([
      { date: '2026-01-01', ndvi: 0.5, validPct: 80, cloudPct: 5 },
      { date: '2026-01-06', ndvi: 0.5, validPct: 45, cloudPct: 40 },
      { date: '2026-01-11', ndvi: 0.5, validPct: 20, cloudPct: 85 },
    ]);
    expect(rows.map((r) => r.quality)).toEqual(['GOOD', 'FAIR', 'POOR']);
  });
});

describe('flagOutliers', () => {
  it('flags a single spike but keeps a sustained move', () => {
    const flagged = flagOutliers(
      sanitiseObservations(series([
        ['2026-01-01', 0.6],
        ['2026-01-06', 0.62],
        ['2026-01-11', 0.2], // isolated spike down, neighbours agree at ~0.6
        ['2026-01-16', 0.61],
        ['2026-01-21', 0.6],
      ])),
    );
    expect(flagged[2].isOutlier).toBe(true);
    expect(flagged[2].outlierReason).toContain('Isolated value');
    expect(flagged.filter((r) => r.isOutlier)).toHaveLength(1);
  });

  it('does not flag a genuine step change', () => {
    const flagged = flagOutliers(
      sanitiseObservations(series([
        ['2026-01-01', 0.6],
        ['2026-01-06', 0.61],
        ['2026-01-11', 0.25],
        ['2026-01-16', 0.24],
        ['2026-01-21', 0.23],
      ])),
    );
    expect(flagged.some((r) => r.isOutlier)).toBe(false);
  });
});

describe('summarise', () => {
  it('reports availability, statistics and trend', () => {
    const smooth = smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-05-01', 0.3],
      ['2026-05-10', 0.4],
      ['2026-05-20', 0.5],
    ]))));
    const summary = summarise(smooth, { now: NOW });
    expect(summary.available).toBe(true);
    expect(summary.count).toBe(3);
    expect(summary.min).toBeCloseTo(0.3, 2);
    expect(summary.max).toBeCloseTo(0.5, 2);
    expect(summary.trend).toBe('RISING');
    expect(summary.dataAgeDays).toBe(daysBetween('2026-05-20', NOW));
  });

  it('says so when there is no usable reading', () => {
    const summary = summarise([], { now: NOW });
    expect(summary.available).toBe(false);
    expect(summary.reason).toMatch(/No valid/);
    expect(summary.latest).toBeNull();
  });

  it('summarises EVI when asked for that index', () => {
    const smooth = smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-05-01', 0.3],
      ['2026-05-10', 0.4],
      ['2026-05-20', 0.5],
    ]))));
    const evi = summarise(smooth, { now: NOW, index: 'evi' });
    expect(evi.available).toBe(true);
    expect(evi.index).toBe('EVI');
    expect(evi.count).toBe(3);
    // The fixture writes EVI at 0.8 x NDVI, so EVI must never equal the NDVI stat.
    expect(evi.max).toBeCloseTo(round2(0.5 * 0.8), 2);
    expect(evi.max).not.toBeCloseTo(0.5, 2);
    expect(evi.trend).toBe('RISING');
  });

  it('ignores rows with no value for the requested index', () => {
    const smooth = smoothSeries(flagOutliers(sanitiseObservations([
      { date: '2026-05-01', ndvi: 0.3, evi: 0.24 },
      { date: '2026-05-10', ndvi: 0.4, evi: null },
    ])));
    const evi = summarise(smooth, { now: NOW, index: 'evi' });
    expect(evi.count).toBe(1);
    expect(evi.latest.date).toBe('2026-05-01');
    expect(evi.change).toBeNull();
  });
});

describe('detectGrowthCycle', () => {
  it('finds green-up, peak and end of season', () => {
    const smooth = smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-02-01', 0.2],
      ['2026-02-10', 0.24],
      ['2026-02-20', 0.45],
      ['2026-03-02', 0.7],
      ['2026-03-12', 0.75],
      ['2026-03-22', 0.55],
      ['2026-04-01', 0.35],
      ['2026-04-11', 0.22],
      ['2026-04-21', 0.2],
    ]))));
    const cycle = detectGrowthCycle(smooth);
    expect(cycle.available).toBe(true);
    expect(cycle.peakOn).toBe('2026-03-12');
    expect(cycle.greenUpOn).toBe('2026-02-20');
    expect(cycle.endOn).toBe('2026-04-01');
    expect(['HIGH', 'MEDIUM', 'LOW']).toContain(cycle.confidence);
    expect(cycle.note).toMatch(/estimates/);
  });

  it('refuses to force a cycle from too little data', () => {
    const smooth = smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-02-01', 0.4],
      ['2026-02-10', 0.45],
    ]))));
    const cycle = detectGrowthCycle(smooth);
    expect(cycle.available).toBe(false);
    expect(cycle.reason).toMatch(/At least 4/);
  });
});

describe('detectDrops', () => {
  it('detects a sharp drop with magnitude, quality and evidence', () => {
    const pairs = [
      ['2026-03-01', 0.72],
      ['2026-03-06', 0.74],
      ['2026-03-11', 0.3],
      ['2026-03-16', 0.31],
      ['2026-03-21', 0.3],
    ];
    const cleaned = smoothSeries(flagOutliers(sanitiseObservations(series(pairs))));
    const drops = detectDrops(cleaned);
    // The fall happens between 2026-03-06 and 2026-03-11, so the drop is dated
    // by that observation and carries its *raw* values on both sides.
    expect(drops).toHaveLength(1);
    expect(drops[0].date).toBe('2026-03-11');
    expect(drops[0].previousDate).toBe('2026-03-06');
    expect(drops[0].ndviBefore).toBeCloseTo(0.74, 2);
    expect(drops[0].ndviAfter).toBeCloseTo(0.3, 2);
    expect(drops[0].eviBefore).toBeCloseTo(0.592, 2);
    expect(drops[0].eviAfter).toBeCloseTo(0.24, 2);
    expect(drops[0].magnitude).toBeGreaterThan(0.4);
    expect(drops[0].evidence.length).toBeGreaterThan(1);
  });

  it('ignores a change smaller than the threshold', () => {
    const drops = detectDrops(smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-03-01', 0.6],
      ['2026-03-06', 0.52],
    ])))));
    expect(drops).toHaveLength(0);
  });

  it('computes recovery after the drop', () => {
    // Six readings, so the centred smoothing window cannot flatten the fall to
    // the point where it stops clearing the sharp-drop threshold.
    const drops = detectDrops(smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-02-24', 0.71],
      ['2026-03-01', 0.73],
      ['2026-03-06', 0.72],
      ['2026-03-11', 0.3],
      ['2026-03-16', 0.42],
      ['2026-03-21', 0.68],
    ])))));
    expect(drops).toHaveLength(1);
    expect(drops[0].date).toBe('2026-03-11');
    expect(drops[0].recoveryFraction).toBeGreaterThan(0.8);
    expect(drops[0].recoveredOn).toBe('2026-03-21');
  });
});

describe('detectGradualDecline', () => {
  it('detects a steady multi-reading decline', () => {
    // 16 readings 7 days apart: 8 green-up, 8 drought-style decline.
    const pairs = [];
    for (let i = 0; i < 16; i += 1) {
      const ndvi = i < 8 ? 0.3 + i * 0.05 : 0.65 - (i - 7) * 0.05;
      pairs.push([addDays('2026-02-01', i * 7), round2(ndvi)]);
    }
    const decline = detectGradualDecline(smoothSeries(flagOutliers(sanitiseObservations(series(pairs)))));
    expect(decline).not.toBeNull();
    expect(decline.gradual).toBe(true);
    expect(decline.delta).toBeLessThan(0);
    expect(decline.explanation).toBeUndefined(); // ordering is handled by analyseSeries
  });

  it('does not call noise a decline', () => {
    const decline = detectGradualDecline(smoothSeries(flagOutliers(sanitiseObservations(series([
      ['2026-03-01', 0.7],
      ['2026-03-11', 0.72],
      ['2026-03-21', 0.68],
      ['2026-03-31', 0.7],
    ])))));
    expect(decline).toBeNull();
  });
});

describe('weatherWindow', () => {
  const weather = {
    available: true,
    source: 'Open-Meteo',
    dataType: 'MODELLED',
    daily: Array.from({ length: 20 }, (_, i) => ({
      date: new Date(Date.UTC(2026, 2, 1 + i)).toISOString().slice(0, 10),
      rainfallMm: 1,
      tempMaxC: 30 + (i % 5),
      tempMinC: 14,
      soilMoisturePct: 20,
    })),
  };

  it('aggregates the requested window', () => {
    const window = weatherWindow(weather, '2026-03-15', 14);
    expect(window.available).toBe(true);
    expect(window.rainfallMm).toBe(14);
    expect(window.tempMaxC).toBe(34);
    expect(window.periodEnd).toBe('2026-03-15');
    expect(window.dataType).toBe('MODELLED');
  });

  it('reports unavailable weather rather than inventing it', () => {
    const window = weatherWindow({ available: false, reason: 'provider down' }, '2026-03-15', 14);
    expect(window.available).toBe(false);
    expect(window.reason).toBe('provider down');
  });
});

describe('explainDrop', () => {
  const dryWeather = {
    available: true,
    source: 'Open-Meteo',
    dataType: 'MODELLED',
    daily: Array.from({ length: 20 }, (_, i) => ({
      date: new Date(Date.UTC(2026, 2, 1 + i)).toISOString().slice(0, 10),
      rainfallMm: 0,
      tempMaxC: 36,
      tempMinC: 20,
      soilMoisturePct: 12,
    })),
  };

  it('calls a low-quality observation a transient dip, not drought', () => {
    const drop = {
      date: '2026-03-15',
      previousDate: '2026-03-10',
      ndviBefore: 0.6,
      ndviAfter: 0.3,
      magnitude: 0.3,
      quality: 'POOR',
      cloudPct: 70,
      validPct: 15,
      recoveryFraction: null,
      evidence: [],
    };
    const explanation = explainDrop(drop, { weather: dryWeather });
    expect(explanation.classification).toBe(CLASSIFICATION.TRANSIENT_DIP);
    expect(explanation.disclaimer).toMatch(/not a confirmed diagnosis/);
    expect(explanation.nextStep).toMatch(/next clear satellite pass/);
  });

  it('uses a recovery to rule out a lasting loss', () => {
    const drop = {
      date: '2026-03-15',
      previousDate: '2026-03-10',
      ndviBefore: 0.7,
      ndviAfter: 0.3,
      magnitude: 0.4,
      quality: 'GOOD',
      cloudPct: 5,
      validPct: 80,
      recoveryFraction: 0.9,
      evidence: [],
    };
    const explanation = explainDrop(drop, { weather: dryWeather });
    expect(explanation.classification).toBe(CLASSIFICATION.TRANSIENT_DIP);
    expect(explanation.confidence).toBe('HIGH');
  });

  it('attributes a dry, hot window to possible heat or dry stress', () => {
    const drop = {
      date: '2026-03-15',
      previousDate: '2026-03-10',
      ndviBefore: 0.55,
      ndviAfter: 0.35,
      magnitude: 0.2,
      quality: 'GOOD',
      cloudPct: 5,
      validPct: 80,
      recoveryFraction: 0.1,
      evidence: [],
    };
    const explanation = explainDrop(drop, { weather: dryWeather });
    expect(explanation.classification).toBe(CLASSIFICATION.POSSIBLE_HEAT_OR_DRY_STRESS);
    expect(explanation.evidence.join(' ')).toMatch(/rainfall/);
  });

  it('offers harvest only as a possibility for a large clean fall', () => {
    const wetWeather = {
      available: true,
      daily: Array.from({ length: 20 }, (_, i) => ({
        date: new Date(Date.UTC(2026, 2, 1 + i)).toISOString().slice(0, 10),
        rainfallMm: 12,
        tempMaxC: 24,
        soilMoisturePct: 30,
      })),
    };
    const drop = {
      date: '2026-03-15',
      previousDate: '2026-03-10',
      ndviBefore: 0.8,
      ndviAfter: 0.35,
      magnitude: 0.45,
      quality: 'GOOD',
      cloudPct: 5,
      validPct: 85,
      recoveryFraction: 0.2,
      evidence: [],
    };
    const explanation = explainDrop(drop, { weather: wetWeather });
    expect(explanation.classification).toBe(CLASSIFICATION.POSSIBLE_HARVEST);
    expect(explanation.explanation).toMatch(/cannot confirm/);
    expect(explanation.confidence).toBe('LOW');
  });

  it('stays unclear when neither weather nor quality decides', () => {
    const drop = {
      date: '2026-03-15',
      previousDate: '2026-03-10',
      ndviBefore: 0.4,
      ndviAfter: 0.22,
      magnitude: 0.18,
      quality: 'FAIR',
      cloudPct: 20,
      validPct: 50,
      recoveryFraction: 0.3,
      evidence: [],
    };
    const explanation = explainDrop(drop, { weather: { available: false, reason: 'none' } });
    expect(explanation.classification).toBe(CLASSIFICATION.UNCLEAR);
  });
});

describe('buildAlerts', () => {
  const base = {
    series: [],
    growthCycle: { available: false },
    weather: null,
    now: NOW,
  };

  it('raises a decline alert and keeps a stable key', () => {
    const drop = {
      date: '2026-06-20',
      previousDate: '2026-06-15',
      ndviBefore: 0.7,
      ndviAfter: 0.3,
      magnitude: 0.4,
      recoveryFraction: 0.2,
      quality: 'GOOD',
      explanation: { classification: CLASSIFICATION.POSSIBLE_HARVEST },
    };
    const alerts = buildAlerts({ ...base, summary: { available: true, latest: { date: '2026-06-20', ndvi: 0.3 } }, drops: [drop] });
    const decline = alerts.find((a) => a.alertType === 'NDVI_DECLINE');
    expect(decline.alertKey).toBe('NDVI_DECLINE:2026-06-20');
  });

  it('raises no-recovery only after the waiting period', () => {
    const drop = {
      date: '2026-06-01',
      previousDate: '2026-05-27',
      ndviBefore: 0.7,
      ndviAfter: 0.3,
      magnitude: 0.4,
      recoveryFraction: 0.1,
      quality: 'GOOD',
      explanation: { classification: CLASSIFICATION.GRADUAL_DECLINE },
    };
    const alerts = buildAlerts({ ...base, summary: { available: true, latest: { date: '2026-06-01', ndvi: 0.3 } }, drops: [drop] });
    expect(alerts.map((a) => a.alertType)).toContain('NO_RECOVERY');
  });

  it('flags a stale observation and never calls it live', () => {
    const alerts = buildAlerts({ ...base, summary: { available: true, latest: { date: '2026-05-01', ndvi: 0.6 } }, drops: [] });
    const stale = alerts.find((a) => a.alertType === 'STALE_OBSERVATION');
    expect(stale).toBeDefined();
    expect(stale.body).toMatch(/not a live reading/);
  });

  it('reports no observation instead of a fabricated one', () => {
    const alerts = buildAlerts({ ...base, summary: { available: false }, drops: [] });
    expect(alerts.map((a) => a.alertType)).toContain('NO_OBSERVATION');
  });
});

describe('analyseSeries', () => {
  it('produces a complete, self-consistent analysis', () => {
    const observations = series([
      ['2026-02-01', 0.2],
      ['2026-02-11', 0.28],
      ['2026-02-21', 0.5],
      ['2026-03-03', 0.72],
      ['2026-03-13', 0.74],
      ['2026-03-23', 0.35],
      ['2026-04-02', 0.33],
      ['2026-04-12', 0.32],
    ]);
    const analysis = analyseSeries({ observations, now: NOW });
    expect(analysis.series).toHaveLength(8);
    expect(analysis.summary.available).toBe(true);
    expect(analysis.growthCycle.available).toBe(true);
    expect(analysis.drops.length).toBeGreaterThan(0);
    expect(analysis.drops[0].explanation.classification).toBeTruthy();
    expect(analysis.alerts.length).toBeGreaterThan(0);
    expect(analysis.quality.readingsUsable).toBe(8);
    expect(analysis.disclaimer).toMatch(/not direct proof/);
  });

  it('returns an EVI summary alongside the NDVI one', () => {
    const analysis = analyseSeries({
      observations: series([
        ['2026-02-01', 0.2],
        ['2026-02-11', 0.28],
        ['2026-02-21', 0.5],
      ]),
      now: NOW,
    });
    expect(analysis.summary.index).toBe('NDVI');
    expect(analysis.eviSummary.available).toBe(true);
    expect(analysis.eviSummary.index).toBe('EVI');
    expect(analysis.eviSummary.count).toBe(analysis.summary.count);
    // EVI is deliberately smaller than NDVI in the fixture, so the two summaries
    // cannot be the same object with the same numbers.
    expect(analysis.eviSummary.latest.evi).toBeCloseTo(0.4, 2);
    expect(analysis.eviSummary.latest.ndvi).toBeCloseTo(0.5, 2);
  });

  it('reports EVI as unavailable rather than empty when no row carries EVI', () => {
    const analysis = analyseSeries({
      observations: [
        { date: '2026-02-01', ndvi: 0.4, evi: null },
        { date: '2026-02-11', ndvi: 0.5, evi: null },
      ],
      now: NOW,
    });
    expect(analysis.summary.available).toBe(true);
    expect(analysis.eviSummary.available).toBe(false);
    expect(analysis.eviSummary.reason).toMatch(/No valid EVI/);
    expect(analysis.eviSummary.latest).toBeNull();
  });

  it('says model training is pending until enough labels exist', () => {
    const analysis = analyseSeries({ observations: series([['2026-02-01', 0.4]]), now: NOW, labelledEventCount: 3 });
    expect(analysis.modelStatus.trained).toBe(false);
    expect(analysis.modelStatus.readyToTrain).toBe(false);
    expect(analysis.modelStatus.note).toMatch(/model training is pending/);
  });

  it('reports readiness (but still no trained model) at 30+ labels', () => {
    const analysis = analyseSeries({ observations: series([['2026-02-01', 0.4]]), now: NOW, labelledEventCount: 31 });
    expect(analysis.modelStatus.readyToTrain).toBe(true);
    expect(analysis.modelStatus.trained).toBe(false);
    expect(analysis.modelStatus.note).toMatch(/No model has been trained/);
  });

  it('handles an empty series without throwing', () => {
    const analysis = analyseSeries({ observations: [], now: NOW });
    expect(analysis.summary.available).toBe(false);
    expect(analysis.growthCycle.available).toBe(false);
    expect(analysis.drops).toEqual([]);
  });
});

describe('date helpers', () => {
  it('round-trips date arithmetic', () => {
    expect(addDays('2026-01-31', 1)).toBe('2026-02-01');
    expect(daysBetween('2026-01-01', '2026-01-31')).toBe(30);
    expect(toEpoch('nope')).toBeNull();
  });
});
