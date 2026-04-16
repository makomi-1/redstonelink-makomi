export type RecordingMetricKey = 'inputPower' | 'outputPower';
export type RecordingXAxisMode = 'relative' | 'raw';
export type RecordingChartRenderMode = 'linear' | 'stepped';
export type RecordingChartWindow = {
  min: number;
  max: number;
};

export type RecordingManifest = {
  recordingId: string;
  title: string;
  startedTick: number;
  endedTick: number;
  sampleEveryTicks: number;
  nodeCount: number;
  sampleCount: number;
  formatVersion: number;
};

export type RecordingNodeInfo = {
  nodeKey: string;
  type: string;
  serial: number;
  displayText: string;
  traceKind: string;
  allocated: boolean;
  retired: boolean;
  online: boolean;
};

export type RecordingSample = {
  tick: number;
  online: boolean;
  active: boolean;
  inputPower: number;
  outputPower: number;
};

export type RecordingSeries = {
  nodeKey: string;
  samples: RecordingSample[];
};

export type RecordingMarker = {
  tick: number;
  label: string;
};

export type RecordingBundle = {
  kind: string;
  manifest: RecordingManifest;
  nodes: RecordingNodeInfo[];
  series: RecordingSeries[];
  markers: RecordingMarker[];
};

export function parseRecordingBundle(textContent: string, kind: string): RecordingBundle | null {
  if (kind !== 'recording') {
    return null;
  }
  try {
    const parsed = JSON.parse(textContent) as Partial<RecordingBundle>;
    if (
      parsed.kind !== 'recordingBundle' ||
      !parsed.manifest ||
      !Array.isArray(parsed.nodes) ||
      !Array.isArray(parsed.series) ||
      !Array.isArray(parsed.markers)
    ) {
      return null;
    }
    return parsed as RecordingBundle;
  } catch {
    return null;
  }
}

export function resolveDisplayTick(
  tick: number,
  startedTick: number,
  xMode: RecordingXAxisMode,
): number {
  return xMode === 'relative' ? tick - startedTick : tick;
}

export function resolveRecordingDomain(
  samples: RecordingSample[],
  startedTick: number,
  xMode: RecordingXAxisMode,
): RecordingChartWindow | null {
  if (samples.length === 0) {
    return null;
  }
  const firstValue = resolveDisplayTick(samples[0].tick, startedTick, xMode);
  const lastValue = resolveDisplayTick(samples[samples.length - 1].tick, startedTick, xMode);
  if (firstValue === lastValue) {
    return {
      min: firstValue - 1,
      max: lastValue + 1,
    };
  }
  return {
    min: Math.min(firstValue, lastValue),
    max: Math.max(firstValue, lastValue),
  };
}

export function resolveCombinedRecordingDomain(
  seriesList: RecordingSeries[],
  startedTick: number,
  xMode: RecordingXAxisMode,
): RecordingChartWindow | null {
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  let found = false;

  for (const series of seriesList) {
    for (const sample of series.samples) {
      const value = resolveDisplayTick(sample.tick, startedTick, xMode);
      min = Math.min(min, value);
      max = Math.max(max, value);
      found = true;
    }
  }

  if (!found) {
    return null;
  }

  if (min === max) {
    return {
      min: min - 1,
      max: max + 1,
    };
  }

  return {
    min,
    max,
  };
}

export function clampRecordingWindow(
  candidate: RecordingChartWindow,
  fullDomain: RecordingChartWindow,
): RecordingChartWindow {
  const fullSpan = Math.max(1, fullDomain.max - fullDomain.min);
  const requestedSpan = Math.max(1, candidate.max - candidate.min);
  if (requestedSpan >= fullSpan) {
    return fullDomain;
  }

  let min = candidate.min;
  let max = candidate.max;
  if (min < fullDomain.min) {
    max += fullDomain.min - min;
    min = fullDomain.min;
  }
  if (max > fullDomain.max) {
    min -= max - fullDomain.max;
    max = fullDomain.max;
  }
  if (min < fullDomain.min) {
    min = fullDomain.min;
  }
  if (max > fullDomain.max) {
    max = fullDomain.max;
  }
  if (max <= min) {
    return {
      min,
      max: min + 1,
    };
  }
  return {
    min,
    max,
  };
}

export function shiftRecordingWindow(
  currentWindow: RecordingChartWindow,
  fullDomain: RecordingChartWindow,
  direction: -1 | 1,
): RecordingChartWindow {
  const span = Math.max(1, currentWindow.max - currentWindow.min);
  const offset = span * 0.25 * direction;
  return clampRecordingWindow(
    {
      min: currentWindow.min + offset,
      max: currentWindow.max + offset,
    },
    fullDomain,
  );
}

export function zoomRecordingWindow(
  currentWindow: RecordingChartWindow,
  fullDomain: RecordingChartWindow,
  factor: number,
): RecordingChartWindow {
  const fullSpan = Math.max(1, fullDomain.max - fullDomain.min);
  const currentSpan = Math.max(1, currentWindow.max - currentWindow.min);
  const nextSpan = Math.min(fullSpan, Math.max(1, currentSpan * factor));
  if (nextSpan >= fullSpan) {
    return fullDomain;
  }
  const center = (currentWindow.min + currentWindow.max) / 2;
  return clampRecordingWindow(
    {
      min: center - nextSpan / 2,
      max: center + nextSpan / 2,
    },
    fullDomain,
  );
}

export function zoomRecordingWindowAt(
  currentWindow: RecordingChartWindow,
  fullDomain: RecordingChartWindow,
  factor: number,
  anchorValue: number,
): RecordingChartWindow {
  const fullSpan = Math.max(1, fullDomain.max - fullDomain.min);
  const currentSpan = Math.max(1, currentWindow.max - currentWindow.min);
  const nextSpan = Math.min(fullSpan, Math.max(1, currentSpan * factor));
  if (nextSpan >= fullSpan) {
    return fullDomain;
  }

  const safeAnchorValue = Number.isFinite(anchorValue)
    ? anchorValue
    : (currentWindow.min + currentWindow.max) / 2;
  const normalizedAnchor = (safeAnchorValue - currentWindow.min) / currentSpan;
  const clampedAnchor = Math.min(1, Math.max(0, normalizedAnchor));
  return clampRecordingWindow(
    {
      min: safeAnchorValue - nextSpan * clampedAnchor,
      max: safeAnchorValue + nextSpan * (1 - clampedAnchor),
    },
    fullDomain,
  );
}

export function translateRecordingWindow(
  currentWindow: RecordingChartWindow,
  fullDomain: RecordingChartWindow,
  deltaValue: number,
): RecordingChartWindow {
  return clampRecordingWindow(
    {
      min: currentWindow.min + deltaValue,
      max: currentWindow.max + deltaValue,
    },
    fullDomain,
  );
}

export function formatXAxisLabel(xMode: RecordingXAxisMode): string {
  return xMode === 'relative' ? 'Tick Δ' : 'Tick';
}
