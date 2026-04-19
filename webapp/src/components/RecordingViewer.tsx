import { type ReactNode, useEffect, useMemo, useState } from 'react';
import RecordingChart from './RecordingChart';
import {
  type RecordingBundle,
  type RecordingChartRenderMode,
  type RecordingChartWindow,
  type RecordingMetricKey,
  clampRecordingWindow,
  type RecordingNodeInfo,
  type RecordingSeries,
  type RecordingXAxisMode,
  formatXAxisLabel,
  resolveCombinedRecordingDomain,
  resolveDisplayTick,
} from '../recordingTypes';
import type { WebThemeId } from '../app/theme';

const DEFAULT_VISIBLE_METRICS: RecordingMetricKey[] = ['inputPower', 'outputPower'];
const MAX_SELECTED_RECORDING_NODES = 5;
const RECORDING_NODE_COLORS = [
  {
    inputColor: 'var(--recording-series-1-input)',
    outputColor: 'var(--recording-series-1-output)',
  },
  {
    inputColor: 'var(--recording-series-2-input)',
    outputColor: 'var(--recording-series-2-output)',
  },
  {
    inputColor: 'var(--recording-series-3-input)',
    outputColor: 'var(--recording-series-3-output)',
  },
  {
    inputColor: 'var(--recording-series-4-input)',
    outputColor: 'var(--recording-series-4-output)',
  },
  {
    inputColor: 'var(--recording-series-5-input)',
    outputColor: 'var(--recording-series-5-output)',
  },
] as const;

type RecordingViewerProps = {
  recordingBundle: RecordingBundle;
  themeId: WebThemeId;
};

/**
 * 录制结果查看器。
 * <p>
 * 当前阶段负责节点切换、曲线显隐、横坐标模式切换与时间窗状态承载，
 * 具体鼠标/滚轮/快捷键交互由图表组件完成。
 * </p>
 */
export default function RecordingViewer({
  recordingBundle,
  themeId,
}: RecordingViewerProps) {
  const [selectedRecordingNodeKeys, setSelectedRecordingNodeKeys] = useState<string[]>([]);
  const [recordingXAxisMode, setRecordingXAxisMode] =
    useState<RecordingXAxisMode>('relative');
  const [recordingChartRenderMode, setRecordingChartRenderMode] =
    useState<RecordingChartRenderMode>('linear');
  const [visibleRecordingMetrics, setVisibleRecordingMetrics] = useState<
    RecordingMetricKey[]
  >(DEFAULT_VISIBLE_METRICS);
  const [recordingWindow, setRecordingWindow] =
    useState<RecordingChartWindow | null>(null);

  useEffect(() => {
    const availableNodeKeys = new Set(recordingBundle.nodes.map((node) => node.nodeKey));
    setSelectedRecordingNodeKeys((currentKeys) => {
      const nextKeys = currentKeys
        .filter((nodeKey) => availableNodeKeys.has(nodeKey))
        .slice(0, MAX_SELECTED_RECORDING_NODES);
      if (nextKeys.length > 0) {
        return nextKeys;
      }
      const firstNodeKey = recordingBundle.nodes[0]?.nodeKey;
      return firstNodeKey ? [firstNodeKey] : [];
    });
  }, [recordingBundle]);

  useEffect(() => {
    setRecordingWindow(null);
  }, [recordingBundle, recordingXAxisMode]);

  const selectedRecordingNodes = useMemo(
    () =>
      selectedRecordingNodeKeys
        .map((nodeKey) => recordingBundle.nodes.find((node) => node.nodeKey === nodeKey) ?? null)
        .filter((node): node is RecordingNodeInfo => node != null),
    [recordingBundle.nodes, selectedRecordingNodeKeys],
  );
  const selectedRecordingSeries = useMemo(
    () =>
      selectedRecordingNodes
        .map((node) => ({
          node,
          series:
            recordingBundle.series.find((series) => series.nodeKey === node.nodeKey) ?? null,
        }))
        .filter(
          (
            entry,
          ): entry is {
            node: RecordingNodeInfo;
            series: RecordingSeries;
          } => entry.series != null,
        ),
    [recordingBundle.series, selectedRecordingNodes],
  );
  const chartNodeSeriesGroups = useMemo(
    () =>
      selectedRecordingSeries.map((entry, index) => {
        const colorPair = RECORDING_NODE_COLORS[index % RECORDING_NODE_COLORS.length];
        return {
          nodeKey: entry.node.nodeKey,
          label: entry.node.displayText,
          samples: entry.series.samples,
          inputColor: colorPair.inputColor,
          outputColor: colorPair.outputColor,
        };
      }),
    [selectedRecordingSeries],
  );
  const fullRecordingDomain = useMemo(
    () =>
      resolveCombinedRecordingDomain(
        selectedRecordingSeries.map((entry) => entry.series),
        recordingBundle.manifest.startedTick,
        recordingXAxisMode,
      ),
    [recordingBundle.manifest.startedTick, recordingXAxisMode, selectedRecordingSeries],
  );
  const activeRecordingWindow = fullRecordingDomain
    ? recordingWindow == null
      ? fullRecordingDomain
      : clampRecordingWindow(recordingWindow, fullRecordingDomain)
    : null;

  function toggleMetric(metric: RecordingMetricKey) {
    setVisibleRecordingMetrics((currentMetrics) =>
      currentMetrics.includes(metric)
        ? currentMetrics.filter((currentMetric) => currentMetric !== metric)
        : [...currentMetrics, metric],
    );
  }

  function toggleRecordingNode(nodeKey: string) {
    setSelectedRecordingNodeKeys((currentNodeKeys) => {
      if (currentNodeKeys.includes(nodeKey)) {
        return currentNodeKeys.filter((currentNodeKey) => currentNodeKey !== nodeKey);
      }
      if (currentNodeKeys.length >= MAX_SELECTED_RECORDING_NODES) {
        return currentNodeKeys;
      }
      return [...currentNodeKeys, nodeKey];
    });
  }

  return (
    <section className="recording-view">
      <div className="recording-summary-grid">
        <article className="recording-summary-card">
          <span className="section-tag">Manifest</span>
          <h3>{recordingBundle.manifest.title}</h3>
          <dl className="recording-meta-grid">
            <div>
              <dt>Format</dt>
              <dd>{recordingBundle.manifest.formatVersion}</dd>
            </div>
            <div>
              <dt>Sample Every</dt>
              <dd>{recordingBundle.manifest.sampleEveryTicks} ticks</dd>
            </div>
            <div>
              <dt>Started</dt>
              <dd>{recordingBundle.manifest.startedTick}</dd>
            </div>
            <div>
              <dt>Ended</dt>
              <dd>{recordingBundle.manifest.endedTick}</dd>
            </div>
            <div>
              <dt>Nodes</dt>
              <dd>{recordingBundle.manifest.nodeCount}</dd>
            </div>
            <div>
              <dt>Samples</dt>
              <dd>{recordingBundle.manifest.sampleCount}</dd>
            </div>
          </dl>
        </article>

        <article className="recording-summary-card">
          <span className="section-tag">Markers</span>
          <h3>录制标记</h3>
          {recordingBundle.markers.length === 0 ? (
            <p className="empty-state">当前没有额外标记。</p>
          ) : (
            <ul className="marker-list">
              {recordingBundle.markers.map((marker) => (
                <li key={`${marker.tick}-${marker.label}`}>
                  <span className="marker-tick">Tick {marker.tick}</span>
                  <span className="marker-label">{marker.label}</span>
                </li>
              ))}
            </ul>
          )}
        </article>
      </div>

      <section className="recording-node-section">
        <div className="recording-node-list">
          <div className="recording-section-header">
            <span className="section-tag">Nodes</span>
            <h3>录制节点 ({selectedRecordingNodeKeys.length}/{MAX_SELECTED_RECORDING_NODES})</h3>
          </div>
          {recordingBundle.nodes.map((node) => {
            const isSelected = selectedRecordingNodeKeys.includes(node.nodeKey);
            const disabled =
              !isSelected &&
              selectedRecordingNodeKeys.length >= MAX_SELECTED_RECORDING_NODES;
            return (
              <button
                key={node.nodeKey}
                type="button"
                className={`recording-node-button${isSelected ? ' is-selected' : ''}`}
                disabled={disabled}
                onClick={() => toggleRecordingNode(node.nodeKey)}
              >
                <span className="recording-node-title">{node.displayText}</span>
                <span className="recording-node-meta">
                  {node.type} #{node.serial} · {node.traceKind}
                </span>
              </button>
            );
          })}
        </div>

        <div className="recording-series-panel">
          <div className="recording-section-header">
            <span className="section-tag">Series</span>
            <h3>同坐标系节点曲线</h3>
          </div>
          {selectedRecordingNodes.length > 0 ? (
            <dl className="recording-meta-grid">
              <div>
                <dt>Selected Nodes</dt>
                <dd>{selectedRecordingNodeKeys.length}</dd>
              </div>
              <div>
                <dt>Chart Limit</dt>
                <dd>{MAX_SELECTED_RECORDING_NODES}</dd>
              </div>
              <div>
                <dt>Series With Samples</dt>
                <dd>{selectedRecordingSeries.length}</dd>
              </div>
              <div>
                <dt>Visible Lines</dt>
                <dd>{selectedRecordingSeries.length * visibleRecordingMetrics.length}</dd>
              </div>
            </dl>
          ) : null}
          {selectedRecordingSeries.length > 0 ? (
            <div className="recording-selection-summary">
              {selectedRecordingSeries.map((entry, index) => {
                const colorPair =
                  RECORDING_NODE_COLORS[index % RECORDING_NODE_COLORS.length];
                return (
                  <div key={entry.node.nodeKey} className="recording-selection-chip">
                    <span className="recording-selection-name">{entry.node.displayText}</span>
                    <span
                      className="recording-legend-stroke"
                      style={{ borderTopColor: colorPair.inputColor }}
                    />
                    <span
                      className="recording-legend-stroke is-dashed"
                      style={{ borderTopColor: colorPair.outputColor }}
                    />
                  </div>
                );
              })}
            </div>
          ) : null}
          {!fullRecordingDomain || !activeRecordingWindow ? (
            <p className="empty-state">当前已选节点暂无样本。</p>
          ) : (
            <RecordingSeriesPanel
              recordingBundle={recordingBundle}
              selectedRecordingSeries={selectedRecordingSeries}
              chartNodeSeriesGroups={chartNodeSeriesGroups}
              recordingChartRenderMode={recordingChartRenderMode}
              setRecordingChartRenderMode={setRecordingChartRenderMode}
              recordingXAxisMode={recordingXAxisMode}
              setRecordingXAxisMode={setRecordingXAxisMode}
              themeId={themeId}
              visibleRecordingMetrics={visibleRecordingMetrics}
              toggleMetric={toggleMetric}
              recordingWindow={activeRecordingWindow}
              fullRecordingDomain={fullRecordingDomain}
              onWindowChange={setRecordingWindow}
              onResetWindow={() => setRecordingWindow(null)}
            />
          )}
        </div>
      </section>
    </section>
  );
}

type RecordingSeriesPanelProps = {
  recordingBundle: RecordingBundle;
  selectedRecordingSeries: Array<{
    node: RecordingNodeInfo;
    series: RecordingSeries;
  }>;
  chartNodeSeriesGroups: Array<{
    nodeKey: string;
    label: string;
    samples: RecordingSeries['samples'];
    inputColor: string;
    outputColor: string;
  }>;
  recordingChartRenderMode: RecordingChartRenderMode;
  setRecordingChartRenderMode: (mode: RecordingChartRenderMode) => void;
  recordingXAxisMode: RecordingXAxisMode;
  setRecordingXAxisMode: (mode: RecordingXAxisMode) => void;
  themeId: WebThemeId;
  visibleRecordingMetrics: RecordingMetricKey[];
  toggleMetric: (metric: RecordingMetricKey) => void;
  recordingWindow: RecordingChartWindow;
  fullRecordingDomain: RecordingChartWindow;
  onWindowChange: (nextWindow: RecordingChartWindow) => void;
  onResetWindow: () => void;
};

function RecordingSeriesPanel({
  recordingBundle,
  selectedRecordingSeries,
  chartNodeSeriesGroups,
  recordingChartRenderMode,
  setRecordingChartRenderMode,
  recordingXAxisMode,
  setRecordingXAxisMode,
  themeId,
  visibleRecordingMetrics,
  toggleMetric,
  recordingWindow,
  fullRecordingDomain,
  onWindowChange,
  onResetWindow,
}: RecordingSeriesPanelProps) {
  return (
    <>
      <div className="recording-toolbar-block">
        <RecordingToolbarRow label="横坐标">
          <MetricToggleButton
            active={recordingXAxisMode === 'relative'}
            label="相对 Tick"
            onClick={() => setRecordingXAxisMode('relative')}
          />
          <MetricToggleButton
            active={recordingXAxisMode === 'raw'}
            label="原始 Tick"
            onClick={() => setRecordingXAxisMode('raw')}
          />
        </RecordingToolbarRow>

        <RecordingToolbarRow label="曲线">
          <MetricToggleButton
            active={visibleRecordingMetrics.includes('inputPower')}
            label="Input Power"
            onClick={() => toggleMetric('inputPower')}
          />
          <MetricToggleButton
            active={visibleRecordingMetrics.includes('outputPower')}
            label="Output Power"
            onClick={() => toggleMetric('outputPower')}
          />
        </RecordingToolbarRow>

        <RecordingToolbarRow label="显示">
          <MetricToggleButton
            active={recordingChartRenderMode === 'linear'}
            label="折线"
            onClick={() => setRecordingChartRenderMode('linear')}
          />
          <MetricToggleButton
            active={recordingChartRenderMode === 'stepped'}
            label="阶跃"
            onClick={() => setRecordingChartRenderMode('stepped')}
          />
        </RecordingToolbarRow>
      </div>

      <p className="chart-interaction-hint">
        滚轮缩放，左键拖拽平移，双击重置；聚焦图表后可用 `←` `→` `+` `-` `0`
        操作时间窗。
      </p>

      <div className="chart-window-hint">
        当前视窗：{recordingWindow.min.toFixed(0)} ~ {recordingWindow.max.toFixed(0)} (
        {formatXAxisLabel(recordingXAxisMode)})
      </div>

      <div className="recording-chart-card">
        <RecordingChart
          nodeSeriesGroups={chartNodeSeriesGroups}
          startedTick={recordingBundle.manifest.startedTick}
          xMode={recordingXAxisMode}
          renderMode={recordingChartRenderMode}
          themeId={themeId}
          visibleMetrics={visibleRecordingMetrics}
          xWindow={recordingWindow}
          fullDomain={fullRecordingDomain}
          onWindowChange={onWindowChange}
          onResetWindow={onResetWindow}
        />
      </div>

      {selectedRecordingSeries.map((entry) => (
        <details className="sample-table-panel" key={entry.node.nodeKey}>
          <summary>
            具体数据列表 - {entry.node.displayText}（{entry.series.samples.length} 条样本）
          </summary>
          <div className="sample-table-wrap">
            <table className="sample-table">
              <thead>
                <tr>
                  <th>{formatXAxisLabel(recordingXAxisMode)}</th>
                  {recordingXAxisMode === 'relative' ? <th>Raw Tick</th> : null}
                  <th>Online</th>
                  <th>Active</th>
                  <th>Input</th>
                  <th>Output</th>
                </tr>
              </thead>
              <tbody>
                {entry.series.samples.map((sample, index) => (
                  <tr key={`${entry.node.nodeKey}-${sample.tick}-${index}`}>
                    <td>
                      {resolveDisplayTick(
                        sample.tick,
                        recordingBundle.manifest.startedTick,
                        recordingXAxisMode,
                      )}
                    </td>
                    {recordingXAxisMode === 'relative' ? <td>{sample.tick}</td> : null}
                    <td>{sample.online ? 'yes' : 'no'}</td>
                    <td>{sample.active ? 'yes' : 'no'}</td>
                    <td>{sample.inputPower}</td>
                    <td>{sample.outputPower}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ))}
    </>
  );
}

type RecordingToolbarRowProps = {
  label: string;
  children: ReactNode;
};

function RecordingToolbarRow({ label, children }: RecordingToolbarRowProps) {
  return (
    <div className="recording-toolbar-row">
      <span className="chart-toolbar-label">{label}</span>
      <div className="chip-group">{children}</div>
    </div>
  );
}

type MetricToggleButtonProps = {
  active: boolean;
  label: string;
  onClick: () => void;
};

function MetricToggleButton({ active, label, onClick }: MetricToggleButtonProps) {
  return (
    <button
      type="button"
      className={`metric-chip${active ? ' is-active' : ''}`}
      onClick={onClick}
    >
      {label}
    </button>
  );
}
