import { type KeyboardEvent as ReactKeyboardEvent, useEffect, useMemo, useRef, useState } from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import {
  type RecordingChartRenderMode,
  type RecordingChartWindow,
  type RecordingMetricKey,
  type RecordingXAxisMode,
  shiftRecordingWindow,
  translateRecordingWindow,
  zoomRecordingWindowAt,
} from '../recordingTypes';
import type { WebThemeId } from '../app/theme';

type RecordingSample = {
  tick: number;
  inputPower: number;
  outputPower: number;
};

type RecordingChartProps = {
  nodeSeriesGroups: RecordingChartNodeSeriesGroup[];
  startedTick: number;
  xMode: RecordingXAxisMode;
  renderMode: RecordingChartRenderMode;
  themeId: WebThemeId;
  visibleMetrics: RecordingMetricKey[];
  xWindow: RecordingChartWindow;
  fullDomain: RecordingChartWindow;
  onWindowChange: (nextWindow: RecordingChartWindow) => void;
  onResetWindow: () => void;
};

type RecordingChartNodeSeriesGroup = {
  nodeKey: string;
  label: string;
  samples: RecordingSample[];
  inputColor: string;
  outputColor: string;
};

const CHART_HEIGHT = 420;
const MIN_CHART_WIDTH = 320;
const KEYBOARD_ZOOM_FACTOR = 0.85;
const WHEEL_ZOOM_FACTOR = 0.82;

/**
 * 录制曲线图。
 * <p>
 * 当前组件负责折线渲染与时间窗交互采集，
 * 包括滚轮缩放、拖拽平移、双击重置与快捷键操作。
 * </p>
 */
export default function RecordingChart({
  nodeSeriesGroups,
  startedTick,
  xMode,
  renderMode,
  themeId,
  visibleMetrics,
  xWindow,
  fullDomain,
  onWindowChange,
  onResetWindow,
}: RecordingChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const hostRef = useRef<HTMLDivElement | null>(null);
  const plotRef = useRef<uPlot | null>(null);
  const currentWindowRef = useRef<RecordingChartWindow>(xWindow);
  const fullDomainRef = useRef<RecordingChartWindow>(fullDomain);
  const dragStateRef = useRef<{
    pointerId: number;
    startOffsetX: number;
    startWindow: RecordingChartWindow;
  } | null>(null);
  const [chartWidth, setChartWidth] = useState<number>(MIN_CHART_WIDTH);
  const chartNodeSeriesGroups = useMemo(
    () => nodeSeriesGroups.filter((group) => group.samples.length > 0),
    [nodeSeriesGroups],
  );
  const emptyStateMessage =
    nodeSeriesGroups.length === 0
      ? '请先在左侧选择 1~5 个节点。'
      : visibleMetrics.length === 0
        ? '请至少启用一条功率曲线。'
        : chartNodeSeriesGroups.length === 0
          ? '当前已选节点暂无样本。'
        : '';

  useEffect(() => {
    currentWindowRef.current = xWindow;
  }, [xWindow]);

  useEffect(() => {
    fullDomainRef.current = fullDomain;
  }, [fullDomain]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) {
      return undefined;
    }
    const resizeObserver = new ResizeObserver((entries) => {
      const nextWidth = Math.max(
        MIN_CHART_WIDTH,
        Math.floor(entries[0]?.contentRect.width ?? MIN_CHART_WIDTH),
      );
      setChartWidth(nextWidth);
    });
    resizeObserver.observe(host);
    return () => resizeObserver.disconnect();
  }, []);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) {
      return undefined;
    }

    plotRef.current?.destroy();
    plotRef.current = null;
    dragStateRef.current = null;
    host.innerHTML = '';

    if (chartNodeSeriesGroups.length === 0 || visibleMetrics.length === 0) {
      return undefined;
    }

    const axisStroke = resolveThemeColor('var(--recording-chart-axis)', host);
    const gridStroke = resolveThemeColor('var(--recording-chart-grid)', host);
    const xValueSet = new Set<number>();
    for (const group of chartNodeSeriesGroups) {
      for (const sample of group.samples) {
        xValueSet.add(resolveXValue(sample.tick, startedTick, xMode));
      }
    }

    const xValues = Array.from(xValueSet).sort((left, right) => left - right);
    const xIndexByValue = new Map<number, number>();
    xValues.forEach((value, index) => {
      xIndexByValue.set(value, index);
    });

    const data: uPlot.AlignedData = [xValues];
    const series: uPlot.Series[] = [
      {
        label: xMode === 'relative' ? 'Tick Δ' : 'Tick',
      },
    ];
    const seriesPathBuilder = resolveSeriesPathBuilder(renderMode);

    for (const group of chartNodeSeriesGroups) {
      const inputValues = new Array<number | null>(xValues.length).fill(null);
      const outputValues = new Array<number | null>(xValues.length).fill(null);

      for (const sample of group.samples) {
        const xValue = resolveXValue(sample.tick, startedTick, xMode);
        const xIndex = xIndexByValue.get(xValue);
        if (xIndex == null) {
          continue;
        }
        inputValues[xIndex] = sample.inputPower;
        outputValues[xIndex] = sample.outputPower;
      }

      if (visibleMetrics.includes('inputPower')) {
        data.push(inputValues);
        series.push({
          label: `${group.label} · Input`,
          stroke: resolveThemeColor(group.inputColor, host),
          width: 2,
          ...(seriesPathBuilder ? { paths: seriesPathBuilder } : {}),
        });
      }

      if (visibleMetrics.includes('outputPower')) {
        data.push(outputValues);
        series.push({
          label: `${group.label} · Output`,
          stroke: resolveThemeColor(group.outputColor, host),
          width: 2,
          dash: [10, 6],
          ...(seriesPathBuilder ? { paths: seriesPathBuilder } : {}),
        });
      }
    }

    const plot = new uPlot(
      {
        width: chartWidth,
        height: CHART_HEIGHT,
        padding: [12, 12, 12, 12],
        legend: {
          show: false,
        },
        cursor: {
          drag: {
            x: false,
            y: false,
            setScale: false,
          },
        },
        scales: {
          x: {
            time: false,
          },
        },
        series,
        axes: [
          {
            label: xMode === 'relative' ? 'Tick Δ' : 'Tick',
            stroke: axisStroke,
            grid: {
              stroke: gridStroke,
            },
          },
          {
            label: 'Power',
            stroke: axisStroke,
            grid: {
              stroke: gridStroke,
            },
          },
        ],
      },
      data,
      host,
    );

    plotRef.current = plot;
    bindInteractionHandlers(plot);
    // 曲线全关再开会重建 uPlot；这里立即回灌当前视窗，避免缩回异常小窗口。
    plot.setScale('x', normalizeWindow(xWindow));

    return () => {
      if (plotRef.current === plot) {
        plotRef.current = null;
      }
      dragStateRef.current = null;
      plot.destroy();
    };
  }, [
    chartNodeSeriesGroups,
    chartWidth,
    renderMode,
    startedTick,
    themeId,
    visibleMetrics,
    xMode,
  ]);

  useEffect(() => {
    plotRef.current?.setScale('x', normalizeWindow(xWindow));
  }, [xWindow]);

  function bindInteractionHandlers(plot: uPlot) {
    const overElement = plot.over;

    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      const currentWindow = currentWindowRef.current;
      const currentDomain = fullDomainRef.current;
      if (!currentWindow || !currentDomain) {
        return;
      }
      const anchorValue = plot.posToVal(clampOffsetX(event.offsetX, plot), 'x');
      const factor = event.deltaY < 0 ? WHEEL_ZOOM_FACTOR : 1 / WHEEL_ZOOM_FACTOR;
      onWindowChange(
        zoomRecordingWindowAt(currentWindow, currentDomain, factor, anchorValue),
      );
    };

    const handlePointerDown = (event: PointerEvent) => {
      if (event.button !== 0) {
        return;
      }
      const currentWindow = currentWindowRef.current;
      dragStateRef.current = {
        pointerId: event.pointerId,
        startOffsetX: clampOffsetX(event.offsetX, plot),
        startWindow: currentWindow,
      };
      overElement.setPointerCapture(event.pointerId);
      containerRef.current?.focus();
    };

    const handlePointerMove = (event: PointerEvent) => {
      const dragState = dragStateRef.current;
      if (!dragState || dragState.pointerId !== event.pointerId) {
        return;
      }
      const startValue = plot.posToVal(dragState.startOffsetX, 'x');
      const currentValue = plot.posToVal(clampOffsetX(event.offsetX, plot), 'x');
      onWindowChange(
        translateRecordingWindow(
          dragState.startWindow,
          fullDomainRef.current,
          startValue - currentValue,
        ),
      );
    };

    const clearDragState = (event: PointerEvent) => {
      const dragState = dragStateRef.current;
      if (!dragState || dragState.pointerId !== event.pointerId) {
        return;
      }
      dragStateRef.current = null;
      if (overElement.hasPointerCapture(event.pointerId)) {
        overElement.releasePointerCapture(event.pointerId);
      }
    };

    const handleDoubleClick = () => {
      onResetWindow();
    };

    overElement.addEventListener('wheel', handleWheel, { passive: false });
    overElement.addEventListener('pointerdown', handlePointerDown);
    overElement.addEventListener('pointermove', handlePointerMove);
    overElement.addEventListener('pointerup', clearDragState);
    overElement.addEventListener('pointercancel', clearDragState);
    overElement.addEventListener('dblclick', handleDoubleClick);
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const currentWindow = currentWindowRef.current;
    const currentDomain = fullDomainRef.current;
    const anchorValue = (currentWindow.min + currentWindow.max) / 2;

    switch (event.key) {
      case 'ArrowLeft':
        event.preventDefault();
        onWindowChange(shiftRecordingWindow(currentWindow, currentDomain, -1));
        break;
      case 'ArrowRight':
        event.preventDefault();
        onWindowChange(shiftRecordingWindow(currentWindow, currentDomain, 1));
        break;
      case '+':
      case '=':
        event.preventDefault();
        onWindowChange(
          zoomRecordingWindowAt(
            currentWindow,
            currentDomain,
            KEYBOARD_ZOOM_FACTOR,
            anchorValue,
          ),
        );
        break;
      case '-':
      case '_':
        event.preventDefault();
        onWindowChange(
          zoomRecordingWindowAt(
            currentWindow,
            currentDomain,
            1 / KEYBOARD_ZOOM_FACTOR,
            anchorValue,
          ),
        );
        break;
      case '0':
        event.preventDefault();
        onResetWindow();
        break;
      default:
        break;
    }
  }

  return (
    <div
      ref={containerRef}
      className="recording-chart-interaction-shell"
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      <div
        ref={hostRef}
        className={`recording-chart-host${emptyStateMessage ? ' is-empty' : ''}`}
      />
      {emptyStateMessage ? (
        <div className="recording-chart-empty-overlay">
          <p className="empty-state">{emptyStateMessage}</p>
        </div>
      ) : null}
    </div>
  );
}

function resolveXValue(tick: number, startedTick: number, xMode: RecordingXAxisMode): number {
  return xMode === 'relative' ? tick - startedTick : tick;
}

/**
 * 根据当前模式选择曲线路径构造器。
 */
function resolveSeriesPathBuilder(
  renderMode: RecordingChartRenderMode,
): uPlot.Series.PathBuilder | undefined {
  if (renderMode !== 'stepped') {
    return undefined;
  }
  return uPlot.paths.stepped?.({
    align: 1,
  });
}

function clampOffsetX(offsetX: number, plot: uPlot): number {
  return Math.max(0, Math.min(plot.bbox.width, offsetX));
}

/**
 * 当录制样本极少时，uPlot 需要一个非零窗口宽度。
 */
function normalizeWindow(window: RecordingChartWindow): RecordingChartWindow {
  if (!Number.isFinite(window.min) || !Number.isFinite(window.max)) {
    return {
      min: 0,
      max: 1,
    };
  }
  if (window.max <= window.min) {
    return {
      min: window.min - 1,
      max: window.min + 1,
    };
  }
  return window;
}

/**
 * uPlot 运行在 canvas 上，不能直接消费 CSS 变量；
 * 这里把 `var(--token)` 解析成当前主题下的真实颜色值。
 */
function resolveThemeColor(color: string, host: HTMLElement): string {
  const normalizedColor = color.trim();
  const variableMatch = /^var\((--[^),\s]+)(?:,\s*([^)]+))?\)$/.exec(
    normalizedColor,
  );
  if (!variableMatch) {
    return normalizedColor;
  }
  const themeRoot = host.closest('[data-theme]') as HTMLElement | null;
  const computedStyle = getComputedStyle(themeRoot ?? host);
  const resolvedColor = computedStyle.getPropertyValue(variableMatch[1]).trim();
  if (resolvedColor.length > 0) {
    return resolvedColor;
  }
  return variableMatch[2]?.trim() ?? normalizedColor;
}
