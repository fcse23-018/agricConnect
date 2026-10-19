import { StressLevel, NDVI_THRESHOLDS, stressDescriptor } from './ndvi.js';

/**
 * Turn a computed vegetation index into something a farmer can act on.
 *
 * Hard rules this module obeys:
 *  1. **It never invents a measurement.** Every number it emits is either the
 *     reading it was given or arithmetic on it (a delta, an age in days).
 *  2. **It never claims to know the cause.** NDVI sees canopy vigour; it cannot
 *     see water, nutrients, pests or disease. The advice is therefore framed as
 *     "what to go and check", not "what is wrong".
 *  3. **It is always honest about the data source.** A MOCK reading is labelled
 *     as demonstration data everywhere it appears, including in the confidence
 *     note, so the advice is never mistaken for a finding from real imagery.
 *
 * Agricultural content is deliberately generic and conservative: it is a
 * triage list, not an agronomic prescription, and the disclaimer says so.
 */

/** A change smaller than this (in NDVI units) is treated as noise, not a trend. */
export const TREND_NOISE = 0.05;

export const TrendDirection = Object.freeze({
  FIRST_READING: 'FIRST_READING',
  IMPROVING: 'IMPROVING',
  DECLINING: 'DECLINING',
  STABLE: 'STABLE',
});

export const AdvicePriority = Object.freeze({
  NOW: 'NOW',
  SOON: 'SOON',
  ROUTINE: 'ROUTINE',
});

/** Inclusive NDVI span of each documented band, used to draw a gauge client-side. */
export const STRESS_BANDS = Object.freeze({
  [StressLevel.HIGH_STRESS]: { min: -1, max: NDVI_THRESHOLDS.HIGH_STRESS_MAX },
  [StressLevel.MODERATE_STRESS]: { min: NDVI_THRESHOLDS.HIGH_STRESS_MAX, max: NDVI_THRESHOLDS.MODERATE_STRESS_MAX },
  [StressLevel.HEALTHY_VEGETATION]: { min: NDVI_THRESHOLDS.MODERATE_STRESS_MAX, max: 1 },
});

/**
 * What the reference scale means for arable land. Published general ranges, given
 * so a farmer can read the number themselves rather than only trusting a colour.
 */
export const NDVI_SCALE_GUIDE = Object.freeze([
  { range: 'below 0.10', meaning: 'Bare soil, rock, water or dead residue.' },
  { range: '0.10 – 0.20', meaning: 'Very sparse or severely stressed vegetation.' },
  { range: '0.20 – 0.45', meaning: 'Thin, establishing or moderately stressed canopy.' },
  { range: '0.45 – 0.70', meaning: 'Healthy, actively growing canopy.' },
  { range: 'above 0.70', meaning: 'Dense, vigorous canopy (common under irrigation).' },
]);

/** What no vegetation index can tell you — the limits a farmer must work around. */
export const NDVI_LIMITS = Object.freeze([
  'It is an average over the whole plot, so a healthy half can hide a failing half — always check the map and walk the ground.',
  'It cannot name the cause: water shortage, waterlogging, nutrient deficiency, salinity, pests and disease can all lower the value.',
  'Bare soil, weeds, crop residue and a mixed pixel along the plot edge all have their own values and pull the average.',
  'It saturates in very dense canopies, so a value that stops rising does not necessarily mean growth has stopped.',
]);

const HIGH_STRESS_GUIDANCE = {
  headline: 'Act now — crop vigour is very low',
  meaning:
    'The canopy over this plot is reflecting very little near-infrared light. Bare ground, dead plants and severely stressed crops all read this low.',
  priority: AdvicePriority.NOW,
  actions: [
    {
      title: 'Walk the plot within 24–48 hours',
      detail:
        'Look at the shape of the problem: the whole plot points at water or heat, patches point at pests, disease or salinity, and strips point at irrigation layout or machinery.',
    },
    {
      title: 'Check soil moisture at 20 cm depth',
      detail:
        'Use a hand auger or the ball test. A water-stressed crop and a waterlogged crop look identical from space but need opposite responses.',
    },
    {
      title: 'Rule out pests and disease',
      detail:
        'Inspect ten plants spread across the plot, turning leaves over. The vegetation index shows damage; it never shows the cause.',
    },
    {
      title: 'Postpone new fertiliser until the water balance is right',
      detail:
        'Nitrogen applied to a water-stressed or waterlogged crop is largely wasted, because the roots cannot take it up.',
    },
  ],
  watchFor: [
    'Pale, yellowing or purpling leaves',
    'Wilting that does not recover in the evening',
    'Stunted or uneven plant height',
    'Chewed leaves, bore holes, lesions, mildew or rust',
    'Surface crusting, cracking or standing water',
  ],
};

const MODERATE_STRESS_GUIDANCE = {
  headline: 'Watch closely — vigour is below average',
  meaning:
    'The canopy is present but thinner or less active than a healthy crop at this stage. It is often the earliest visible sign of a problem, and it is the cheapest stage to fix.',
  priority: AdvicePriority.SOON,
  actions: [
    {
      title: 'Compare parts of the plot',
      detail:
        'Walk a diagonal across the plot and compare the weaker area with the stronger one. Note the soil, slope and irrigation differences between them.',
    },
    {
      title: 'Check irrigation uniformity',
      detail:
        'Look for blocked emitters, uneven furrows, dry head-lands and taps left closed. Uneven water is the most common cause of a patchy plot.',
    },
    {
      title: 'Confirm the fertiliser programme and its timing',
      detail:
        'Check that the last application was at the right rate and stage, and that it was not washed away by heavy rain.',
    },
    {
      title: 'Scout for early pests and disease',
      detail:
        'Small infestations are far cheaper to treat now than after the canopy closes.',
    },
  ],
  watchFor: [
    'Thin or patchy emergence',
    'Slow growth compared with the rest of the farm',
    'Early leaf discolouration',
    'Weed pressure competing with the crop',
  ],
};

const HEALTHY_GUIDANCE = {
  headline: 'On track — vigour is healthy',
  meaning:
    'The canopy is dense and actively growing. This is the response you want to protect, so the work now is to keep conditions steady and keep records.',
  priority: AdvicePriority.ROUTINE,
  actions: [
    {
      title: 'Keep the current water and fertiliser plan',
      detail:
        'A healthy reading means the current regime is working. Change one thing at a time if you must change anything, so you can tell what had the effect.',
    },
    {
      title: 'Keep scouting weekly',
      detail:
        'A good average can still hide a localised problem. Walk the plot and check the edges, low spots and head-lands.',
    },
    {
      title: 'Record inputs, dates and yields',
      detail:
        'Paired with a run of satellite readings, this is what lets you tell which practices actually pay on your land.',
    },
  ],
  watchFor: [
    'Any area that looks duller than its surroundings',
    'New weeds along fence lines and head-lands',
    'Signs of water stress at the hottest part of the day',
  ],
};

/** The retest cadence is in the guidance for every band — the single most useful habit. */
const RETEST_ACTION = {
  [StressLevel.HIGH_STRESS]: {
    title: 'Re-run the index in 5–7 days',
    detail:
      'After you act, a second reading shows whether the intervention worked. A rising value means the crop is recovering.',
  },
  [StressLevel.MODERATE_STRESS]: {
    title: 'Re-run the index in 7–10 days',
    detail:
      'That is roughly one satellite revisit. Two readings in a row show direction, which one reading never can.',
  },
  [StressLevel.HEALTHY_VEGETATION]: {
    title: 'Re-run the index every 10–14 days',
    detail:
      'Regular readings build the trend line for the season and warn you early if conditions change.',
  },
};

const RECORD_ACTION = {
  title: 'Record what you actually find in the field',
  detail:
    'Your field records sit alongside the satellite reading, so the next time the value moves you can see which observation matched it.',
};

/** Compare this reading with the previous one for the same plot. */
export function detectTrend(current, previous) {
  if (current === null || current === undefined || !Number.isFinite(Number(current))) {
    return { direction: TrendDirection.FIRST_READING, delta: null, previousValue: null };
  }
  if (previous === null || previous === undefined || !Number.isFinite(Number(previous))) {
    return {
      direction: TrendDirection.FIRST_READING,
      delta: null,
      previousValue: null,
      note: 'This is the first reading we hold for this plot, so there is no trend yet. The next reading will be compared with this one.',
    };
  }
  const delta = Number((Number(current) - Number(previous)).toFixed(4));
  if (Math.abs(delta) < TREND_NOISE) return { direction: TrendDirection.STABLE, delta, previousValue: Number(previous) };
  return {
    direction: delta > 0 ? TrendDirection.IMPROVING : TrendDirection.DECLINING,
    delta,
    previousValue: Number(previous),
  };
}

/**
 * Confidence is about the *measurement*, not the crop: how reliable this number is
 * as a picture of the plot. Cloud, a thin revisit and demonstration data all
 * degrade it, and each is named in the note.
 */
function confidenceFor({ source, cloudCover }) {
  const isMock = String(source ?? '').toUpperCase() === 'MOCK';
  const cloud = cloudCover === null || cloudCover === undefined ? null : Number(cloudCover);

  if (isMock) {
    return {
      level: 'LOW',
      note:
        'Demonstration reading: no satellite credentials are configured, so this value comes from the built-in ' +
        'simulator. Treat it as a placeholder for the feature, not as a picture of your plot. The guidance below ' +
        'describes what such a value would mean if it were a real observation.',
    };
  }
  if (cloud !== null && cloud > 50) {
    return {
      level: 'LOW',
      note: `Cloud cover was ${cloud}% for this observation, so the average rests on relatively few clear pixels. Prefer the next clear revisit before acting on a small change.`,
    };
  }
  if (cloud === null) {
    return {
      level: 'MEDIUM',
      note: 'The provider did not report cloud cover for this observation, so the average cannot be qualified. Check the map for missing or hazy areas.',
    };
  }
  if (cloud <= 20) {
    return {
      level: 'HIGH',
      note: `Cloud cover was ${cloud}%, so this average rests on clear pixels across the plot.`,
    };
  }
  return {
    level: 'MEDIUM',
    note: `Cloud cover was ${cloud}%, so a few pixels may be hazy. Small changes between readings should be read with care.`,
  };
}

/** Whole days between the observation date and now — never negative. */
function ageInDays(satelliteDate, today) {
  if (!satelliteDate) return null;
  const observed = new Date(`${satelliteDate}T00:00:00Z`);
  if (Number.isNaN(observed.getTime())) return null;
  const diff = Math.floor((today.getTime() - observed.getTime()) / 86_400_000);
  return diff < 0 ? 0 : diff;
}

function guidanceFor(stressLevel) {
  switch (stressLevel) {
    case StressLevel.HIGH_STRESS:
      return HIGH_STRESS_GUIDANCE;
    case StressLevel.MODERATE_STRESS:
      return MODERATE_STRESS_GUIDANCE;
    case StressLevel.HEALTHY_VEGETATION:
      return HEALTHY_GUIDANCE;
    default:
      return null;
  }
}

/**
 * Build the advisory block returned alongside a generated NDVI reading.
 *
 * @param {object} input
 * @param {number} input.meanNdviValue  the measured value (required)
 * @param {string} input.stressLevel    `classifyNdvi()` output; re-derived if absent
 * @param {object|null} input.previous  `{ value, date }` of the previous reading, if any
 * @param {number|null} input.areaHectares  area of the boundary the value averages over
 * @param {string|null} input.plotName
 * @param {string|null} input.satelliteDate  date-only observation date
 * @param {number|null} input.cloudCover
 * @param {string|null} input.source    `SENTINEL-2` or `MOCK`
 * @param {Date} [input.today]  injectable clock (keeps tests deterministic)
 * @returns {object|null} null when there is no usable value to advise on
 */
export function buildNdviAdvice({
  meanNdviValue,
  stressLevel = null,
  previous = null,
  areaHectares = null,
  plotName = null,
  satelliteDate = null,
  cloudCover = null,
  source = null,
  today = new Date(),
} = {}) {
  const value = meanNdviValue === null || meanNdviValue === undefined ? null : Number(meanNdviValue);
  if (value === null || !Number.isFinite(value)) return null;

  const level = stressLevel ?? (value < NDVI_THRESHOLDS.HIGH_STRESS_MAX
    ? StressLevel.HIGH_STRESS
    : value < NDVI_THRESHOLDS.MODERATE_STRESS_MAX
      ? StressLevel.MODERATE_STRESS
      : StressLevel.HEALTHY_VEGETATION);

  const descriptor = stressDescriptor(level);
  const guidance = guidanceFor(level) ?? HEALTHY_GUIDANCE;
  const trend = detectTrend(value, previous?.value ?? null);
  const confidence = confidenceFor({ source, cloudCover });
  const daysSince = ageInDays(satelliteDate, today);

  const trendSentence = {
    [TrendDirection.IMPROVING]: `Up ${trend.delta} on the previous reading — the crop is recovering.`,
    [TrendDirection.DECLINING]: `Down ${Math.abs(trend.delta)} on the previous reading — keep watching this plot.`,
    [TrendDirection.STABLE]: 'Essentially unchanged from the previous reading.',
    [TrendDirection.FIRST_READING]: 'This is the first reading for this plot.',
  }[trend.direction];

  const summaryParts = [
    `The vegetation index for ${plotName ? `“${plotName}”` : 'this plot'} is ${value.toFixed(2)} (${descriptor.label.toLowerCase()}).`,
    trendSentence,
  ];
  if (daysSince !== null) {
    summaryParts.push(
      daysSince === 0 ? 'The observation is from today.' : `The observation is ${daysSince} day${daysSince === 1 ? '' : 's'} old.`,
    );
  }

  return {
    /** Which band the value falls in and what that band means agronomically. */
    assessment: {
      value,
      stressLevel: level,
      label: descriptor.label,
      colour: descriptor.colour,
      headline: guidance.headline,
      meaning: guidance.meaning,
      band: STRESS_BANDS[level] ?? null,
      summary: summaryParts.join(' '),
    },
    /** Ordered, concrete things to do; `priority` drives emphasis in the client. */
    priority: guidance.priority,
    actions: [...guidance.actions, RETEST_ACTION[level] ?? RETEST_ACTION[StressLevel.MODERATE_STRESS], RECORD_ACTION],
    watchFor: guidance.watchFor,
    /** Direction of travel versus the plot's previous reading. */
    trend: {
      ...trend,
      previousDate: previous?.date ?? null,
      note: trend.note
        ?? `Compared with the previous reading of ${trend.previousValue} on ${previous?.date ?? 'an earlier date'}.`,
    },
    /** Everything needed to judge the reading itself, including its age and source. */
    context: {
      plotName,
      areaHectares,
      observedOn: satelliteDate,
      daysSinceObservation: daysSince,
      cloudCoverPercent: cloudCover === null || cloudCover === undefined ? null : Number(cloudCover),
      source,
      sourceIsSimulated: String(source ?? '').toUpperCase() === 'MOCK',
    },
    confidence,
    /** Read-the-number-yourselves material. */
    scaleGuide: NDVI_SCALE_GUIDE.map((s) => ({ ...s })),
    limits: [...NDVI_LIMITS],
    disclaimer: 'This is general guidance from a vegetation index, not a site-specific agronomic recommendation.',
  };
}

export default { buildNdviAdvice, detectTrend, TREND_NOISE, TrendDirection, AdvicePriority, STRESS_BANDS };
