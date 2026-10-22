import { describe, it, expect, beforeEach, afterEach, jest } from '@jest/globals';

/**
 * Google Earth Engine Sentinel-2 adapter.
 *
 * What these tests protect:
 *
 *  - **The honesty rule.** With no credentials `computeNdvi` must return a value
 *    tagged `source: 'MOCK', isSimulated: true` and say *why* Real Earth Engine
 *    was not used. A simulated reading that looks like Sentinel-2 is the single
 *    worst failure this module could have.
 *  - **Credential resolution.** A service-account key is the only thing Earth
 *    Engine accepts; a missing file, malformed JSON, or a non-service-account key
 *    (`authorized_user`) must each be reported as a plain reason rather than
 *    throwing, so the API starts and degrades instead of crashing.
 *  - **The index maths.** EVI has a denominator that can approach zero; it is
 *    pinned here against hand-computed values.
 *  - **The real path's shape.** With a stubbed Earth Engine client, one clear
 *    pass produces the documented fields — NDVI and EVI means, the imagery's own
 *    acquisition date (not today's date), the valid-pixel coverage, and the
 *    `SENTINEL-2` / `isSimulated: false` tags.
 *  - **Cloud handling.** A pass above the cloud threshold FAILS with a reason
 *    instead of quietly returning a value; a fully-masked pass is reported as
 *    such rather than as a zero average.
 */

/* ------------------------- stubbed Earth Engine --------------------------- */

/**
 * A tiny fake of the parts of the Earth Engine API this module touches.
 *
 * It is deliberately thin: the tests assert on *our* mapping of Earth Engine's
 * results, not on Earth Engine's behaviour, which is Google's to test.
 *
 * `evaluate` hands back values from a queue in call order, so a test can say
 * "the collection has 3 images, the mean reducer returns X, the count reducer
 * returns Y" and check exactly how those are turned into one observation. The
 * queue makes the ordering explicit rather than relying on a single stub value
 * that every call would return.
 */
let eeBehaviour = {};
let evaluateQueue = [];

function makeFakeEe() {
  const chainable = () => {
    const obj = {
      select: () => obj,
      updateMask: () => obj,
      addBands: () => obj,
      rename: () => obj,
      normalizedDifference: () => obj,
      expression: () => obj,
      copyProperties: () => obj,
      gte: () => obj,
      get: (name) => name,
      filterBounds: () => obj,
      filterDate: () => obj,
      map: () => obj,
      sort: () => obj,
      linkCollection: () => obj,
      first: () => obj,
      size: () => obj,
      // Shift the next queued value; an empty queue yields undefined, which the
      // module treats as "no data" — the honest interpretation.
      evaluate: (cb) => {
        const next = evaluateQueue.shift();
        if (next instanceof Error) return cb(null, next);
        return cb(next, null);
      },
      reduceRegion: () => obj,
    };
    return obj;
  };

  return {
    data: {
      authenticateViaPrivateKey: (key, onSuccess, onError) => {
        eeBehaviour.authenticatedWith = key;
        if (eeBehaviour.authError) return onError(new Error(eeBehaviour.authError));
        return onSuccess();
      },
    },
    initialize: (_a, _b, onSuccess) => onSuccess(),
    Geometry: {
      Polygon: (coords) => {
        eeBehaviour.lastGeometry = coords;
        return chainable();
      },
      MultiPolygon: (coords) => {
        eeBehaviour.lastGeometry = coords;
        return chainable();
      },
    },
    ImageCollection: () => chainable(),
    Image: () => chainable(),
    Feature: (_g, props) => ({ properties: props }),
    Dictionary: (obj) => ({ ...chainable(), evalValue: obj }),
    Reducer: {
      mean: () => ({ combine: () => 'combined' }),
      count: () => 'count',
    },
    Date: () => ({
      advance: () => ({ advance: () => 'date' }),
      format: () => 'YYYY-MM-dd',
    }),
  };
}

jest.unstable_mockModule('@google/earthengine', () => ({
  default: makeFakeEe(),
}));

const { config } = await import('../../src/config/index.js');
const {
  computeNdvi,
  geeAvailability,
  computeEviFromBands,
  estimateAreaHectares,
  fetchGeeSeries,
  resetEarthEngine,
} = await import('../../src/services/gee.js');

/** A closed GeoJSON square around Gaborone. */
const SQUARE = {
  type: 'Polygon',
  coordinates: [[
    [25.90, -24.64],
    [25.92, -24.64],
    [25.92, -24.62],
    [25.90, -24.62],
    [25.90, -24.64],
  ]],
};

const SAVED = {};
beforeEach(() => {
  eeBehaviour = {};
  evaluateQueue = [];
  resetEarthEngine();
  SAVED.enabled = config.gee.enabled;
  SAVED.email = config.gee.serviceAccountEmail;
  SAVED.key = config.gee.privateKey;
  SAVED.project = config.gee.projectId;
  SAVED.problem = config.gee.credentialProblem;
  SAVED.lookback = config.gee.lookbackDays;

  // Default: Earth Engine OFF (the repository default).
  config.gee.enabled = false;
  config.gee.serviceAccountEmail = '';
  config.gee.privateKey = '';
  config.gee.projectId = '';
  config.gee.credentialProblem = null;
});

afterEach(() => {
  config.gee.enabled = SAVED.enabled;
  config.gee.serviceAccountEmail = SAVED.email;
  config.gee.privateKey = SAVED.key;
  config.gee.projectId = SAVED.project;
  config.gee.credentialProblem = SAVED.problem;
  config.gee.lookbackDays = SAVED.lookback;
});

/** Put the config into the "credentials present" state the real path needs. */
function enableGee() {
  config.gee.enabled = true;
  config.gee.serviceAccountEmail = 'agri@agriconnect-512210.iam.gserviceaccount.com';
  config.gee.privateKey = '-----BEGIN PRIVATE KEY-----\nMIIfake\n-----END PRIVATE KEY-----\n';
  config.gee.projectId = 'agriconnect-512210';
}

/* ------------------------------- availability ----------------------------- */

describe('geeAvailability', () => {
  it('explains that Earth Engine is switched off, rather than just failing', () => {
    const a = geeAvailability();
    expect(a.ok).toBe(false);
    expect(a.reason).toMatch(/switched off/i);
  });

  it('is ok once all four settings are present', () => {
    enableGee();
    expect(geeAvailability()).toEqual({ ok: true });
  });

  it('names the missing piece when one is absent', () => {
    enableGee();
    config.gee.projectId = '';
    expect(geeAvailability().reason).toMatch(/project id/i);

    enableGee();
    config.gee.serviceAccountEmail = '';
    expect(geeAvailability().reason).toMatch(/email/i);

    enableGee();
    config.gee.privateKey = '';
    expect(geeAvailability().reason).toMatch(/key is configured/i);
  });

  it('surfaces a credential-loading problem verbatim', () => {
    config.gee.enabled = true;
    config.gee.credentialProblem = 'GEE_KEY_FILE could not be read (ENOENT)';
    expect(geeAvailability().reason).toMatch(/ENOENT/);
  });
});

/* ------------------------------ EVI reference ----------------------------- */

describe('computeEviFromBands', () => {
  it('matches the documented EVI formula', () => {
    // denominator = 0.5 + 0.6 - 0.375 + 1 = 1.725; 2.5*(0.5-0.1) = 1.0
    expect(computeEviFromBands(0.5, 0.1, 0.05)).toBeCloseTo(0.5797, 4);
  });

  it('is zero when NIR equals RED, as the formula requires', () => {
    expect(computeEviFromBands(0.3, 0.3, 0.1)).toBe(0);
  });

  it('returns null for non-finite inputs rather than a plausible number', () => {
    expect(computeEviFromBands(null, 0.1, 0.05)).toBeNull();
    expect(computeEviFromBands(0.5, undefined, 0.05)).toBeNull();
    expect(computeEviFromBands('', 0.1, 0.05)).toBeNull();
  });

  it('refuses the degenerate zero denominator instead of returning Infinity', () => {
    // n + 6r - 7.5b + 1 = 0  ->  pick n=0, r=0, b=1/7.5
    const b = 1 / 7.5;
    expect(computeEviFromBands(0, 0, b)).toBeNull();
  });
});

/* ------------------------------ mock honesty ------------------------------ */

describe('computeNdvi without credentials', () => {
  it('labels its output MOCK and simulated, and says why', async () => {
    const res = await computeNdvi({ plotId: 'plot-1', geoJsonBoundary: SQUARE });

    expect(res.source).toBe('MOCK');
    expect(res.isSimulated).toBe(true);
    // The reason Earth Engine was not used must travel with the value.
    expect(res.providerReason).toMatch(/switched off/i);
    expect(res.meanNdviValue).not.toBeNull();
  });

  it('never labels a simulated reading as SENTINEL-2', async () => {
    const res = await computeNdvi({ plotId: 'plot-1', geoJsonBoundary: SQUARE });
    expect(res.source).not.toBe('SENTINEL-2');
  });

  it('is deterministic for the same plot', async () => {
    const a = await computeNdvi({ plotId: 'plot-9', geoJsonBoundary: SQUARE });
    const b = await computeNdvi({ plotId: 'plot-9', geoJsonBoundary: SQUARE });
    expect(a.meanNdviValue).toBe(b.meanNdviValue);
  });

  it('explains a failed boundary instead of computing on it', async () => {
    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: { type: 'Point', coordinates: [1, 2] } });
    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
    expect(res.source).toBe('VALIDATION');
  });

  it('explains a missing boundary', async () => {
    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: null });
    expect(res.status).toBe('FAILED');
    expect(res.failureReason).toMatch(/required/i);
  });
});

/* -------------------------------- real path -------------------------------- */

describe('computeNdvi with credentials', () => {
  it('returns NDVI and EVI with the imagery\u2019s own date and tiled tags', async () => {
    enableGee();
    // The module evaluates in this order: collection size, then the parallel
    // block of [mean indices, valid count, total count, metadata].
    evaluateQueue = [
      3, // collection size
      { NDVI: 0.4213, EVI: 0.2871 }, // mean over masked indices
      { NDVI: 812 }, // valid (clear) pixel count
      { constant: 1000 }, // boundary pixel count, unmasked
      { sceneCloud: 12.5, productId: 'S2B_MSIL2A_20261014', timeStart: Date.UTC(2026, 9, 14) },
    ];

    const res = await computeNdvi({ plotId: 'plot-1', geoJsonBoundary: SQUARE });

    expect(res.status).toBe('GENERATED');
    expect(res.source).toBe('SENTINEL-2');
    expect(res.isSimulated).toBe(false);
    expect(res.meanNdviValue).toBeCloseTo(0.4213, 4);
    expect(res.meanEviValue).toBeCloseTo(0.2871, 4);
    // The imagery's date, not today's.
    expect(res.satelliteDate).toBe('2026-10-14');
    // 812 of 1000 boundary pixels survived masking.
    expect(res.validPixelPct).toBeCloseTo(81.2, 1);
    expect(res.pixelCount).toBe(812);
    expect(res.sceneId).toBe('S2B_MSIL2A_20261014');
    expect(res.cloudCover).toBe(12.5);
  });

  it('FAILS a too-cloudy pass with a dated reason, rather than returning a value', async () => {
    enableGee();
    evaluateQueue = [
      1,
      { NDVI: 0.4, EVI: 0.3 },
      { NDVI: 500 },
      { constant: 1000 },
      { sceneCloud: 94, productId: 'S2_cloudy', timeStart: Date.UTC(2026, 9, 14) },
    ];

    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });

    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
    expect(res.failureReason).toMatch(/94%/);
    expect(res.failureReason).toMatch(/2026-10-14/);
    expect(res.isSimulated).toBe(false);
  });

  it('reports a fully-masked pass as such, not as a zero average', async () => {
    enableGee();
    evaluateQueue = [
      1,
      { NDVI: 0, EVI: 0 }, // an empty reduction returns 0, not null
      { NDVI: 0 }, // ...and zero valid pixels is the reliable signal
      { constant: 1000 },
      { sceneCloud: 5, productId: 'S2_masked', timeStart: Date.UTC(2026, 9, 14) },
    ];

    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });

    expect(res.status).toBe('FAILED');
    // Crucially NOT 0 — Earth Engine returns 0 for a mean over an empty set, and
    // reporting that as NDVI would read as "bare soil" instead of "not seen".
    expect(res.meanNdviValue).toBeNull();
    expect(res.failureReason).toMatch(/cloud or cloud shadow/i);
  });

  it('decides usability from the pixel count, not the mean', async () => {
    enableGee();
    // The trap this guards: a plausible-looking NDVI with zero valid pixels.
    // Trusting the mean here would publish 0.4 from a completely masked pass.
    evaluateQueue = [
      1,
      { NDVI: 0.4, EVI: 0.3 },
      { NDVI: 0 },
      { constant: 1000 },
      { sceneCloud: 2, productId: 'S2_x', timeStart: Date.UTC(2026, 9, 14) },
    ];

    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });

    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
  });

  it('reports an empty collection as no imagery, with the window it searched', async () => {
    enableGee();
    config.gee.lookbackDays = 45;
    evaluateQueue = [0]; // size() === 0

    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });

    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
    expect(res.failureReason).toMatch(/45 days/);
  });

  it('passes the service-account key through to the client', async () => {
    enableGee();
    evaluateQueue = [0];
    await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });
    expect(eeBehaviour.authenticatedWith?.client_email).toBe(config.gee.serviceAccountEmail);
    expect(eeBehaviour.authenticatedWith?.project_id).toBe(config.gee.projectId);
    expect(eeBehaviour.authenticatedWith?.private_key).toBe(config.gee.privateKey);
  });

  it('reports an authentication failure as a failure, never a value', async () => {
    enableGee();
    eeBehaviour.authError = 'invalid_grant';
    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });
    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
    expect(res.source).toBe('SENTINEL-2');
    expect(res.isSimulated).toBe(false);
    // The raw error is kept for the log, out of the user-facing reason.
    expect(res.internal).toMatch(/invalid_grant/);
    expect(res.failureReason).not.toMatch(/invalid_grant/);
  });

  it('reports an Earth Engine evaluation error as a failure, not a value', async () => {
    enableGee();
    evaluateQueue = [new Error('computation timed out')];
    const res = await computeNdvi({ plotId: 'p', geoJsonBoundary: SQUARE });
    expect(res.status).toBe('FAILED');
    expect(res.meanNdviValue).toBeNull();
    expect(res.failureReason).toMatch(/could not be processed/i);
  });
});

/* ------------------------------ series fetch ------------------------------ */

describe('fetchGeeSeries', () => {
  it('is unavailable without credentials, with a reason', async () => {
    const res = await fetchGeeSeries({ geoJsonBoundary: SQUARE });
    expect(res.available).toBe(false);
    expect(res.readings).toEqual([]);
    expect(res.reason).toMatch(/switched off/i);
  });

  it('is unavailable for an unusable boundary', async () => {
    const res = await fetchGeeSeries({ geoJsonBoundary: { type: 'Polygon', coordinates: [] } });
    expect(res.available).toBe(false);
  });

  it('drops all-cloud passes instead of recording a zero NDVI for them', async () => {
    enableGee();
    // The live bug this pins: with a 100%-cloud pass, every pixel is masked, and
    // Earth Engine returns 0 for the mean. Five such rows arrived as
    // `ndvi: 0` — reading as bare soil — before the pixel count was used as the
    // test of whether anything was actually measured.
    evaluateQueue = [
      {
        features: [
          { properties: { date: '2026-09-05', ndvi: 0, evi: null, pixels: 0, cloudPct: 99.99 } },
          { properties: { date: '2026-09-10', ndvi: 0.2257, evi: 0.5667, pixels: 44848, cloudPct: 19.17 } },
        ],
      },
    ];

    const res = await fetchGeeSeries({ geoJsonBoundary: SQUARE, days: 60 });

    expect(res.available).toBe(true);
    expect(res.source).toBe('SENTINEL-2');
    expect(res.isSimulated).toBe(false);
    // Only the real observation survives.
    expect(res.readings).toHaveLength(1);
    expect(res.readings[0].date).toBe('2026-09-10');
    expect(res.readings[0].ndvi).toBeCloseTo(0.2257, 4);
    expect(res.readings.every((r) => r.ndvi !== 0)).toBe(true);
  });

  it('is unavailable when every pass was cloud-masked, rather than empty-available', async () => {
    enableGee();
    evaluateQueue = [
      {
        features: [
          { properties: { date: '2026-09-05', ndvi: 0, evi: null, pixels: 0, cloudPct: 99.99 } },
        ],
      },
    ];

    const res = await fetchGeeSeries({ geoJsonBoundary: SQUARE, days: 60 });

    expect(res.available).toBe(false);
    expect(res.readings).toEqual([]);
    expect(res.reason).toMatch(/no clear sentinel-2 passes/i);
  });
});

/* -------------------------------- geometry -------------------------------- */

describe('estimateAreaHectares', () => {
  it('measures a known square', () => {
    // 0.02 deg lon x 0.02 deg lat near -24.63: ~2.02 km x 2.23 km ~ 450 ha
    const ha = estimateAreaHectares(SQUARE);
    expect(ha).toBeGreaterThan(440);
    expect(ha).toBeLessThan(460);
  });

  it('returns null rather than 0 for an unusable ring', () => {
    expect(estimateAreaHectares({ type: 'Polygon', coordinates: [] })).toBeNull();
  });
});
