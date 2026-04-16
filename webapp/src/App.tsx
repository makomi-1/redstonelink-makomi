import { useEffect, useState } from 'react';

type BridgePingPayload = {
  status: string;
  modId: string;
  bridgeVersion: string;
  mode: string;
  startedAtEpochMillis: number;
  baseUrl: string;
};

type StorageEntrySummary = {
  kind: string;
  fileName: string;
  relativePath: string;
  compressed: boolean;
  sizeBytes: number;
  lastModifiedEpochMillis: number;
};

type StorageCategorySummary = {
  kind: string;
  label: string;
  directoryName: string;
  fileExtension: string;
  compressed: boolean;
  entryCount: number;
  entries: StorageEntrySummary[];
};

type StorageIndexPayload = {
  status: string;
  rootPath: string;
  refreshedAtEpochMillis: number;
  categories: StorageCategorySummary[];
};

type StorageEntryPayload = {
  status: string;
  kind: string;
  label: string;
  fileName: string;
  relativePath: string;
  compressed: boolean;
  contentEncoding: string;
  sizeBytes: number;
  lastModifiedEpochMillis: number;
  textContent: string;
};

type RecordingManifest = {
  recordingId: string;
  title: string;
  startedTick: number;
  endedTick: number;
  sampleEveryTicks: number;
  nodeCount: number;
  sampleCount: number;
  formatVersion: number;
};

type RecordingNodeInfo = {
  nodeKey: string;
  type: string;
  serial: number;
  displayText: string;
  traceKind: string;
  allocated: boolean;
  retired: boolean;
  online: boolean;
};

type RecordingSample = {
  tick: number;
  online: boolean;
  active: boolean;
  inputPower: number;
  outputPower: number;
};

type RecordingSeries = {
  nodeKey: string;
  samples: RecordingSample[];
};

type RecordingMarker = {
  tick: number;
  label: string;
};

type RecordingBundle = {
  kind: string;
  manifest: RecordingManifest;
  nodes: RecordingNodeInfo[];
  series: RecordingSeries[];
  markers: RecordingMarker[];
};

type InitialSelection = {
  kind: string;
  fileName: string;
} | null;

function formatStartedAt(epochMillis: number): string {
  if (!Number.isFinite(epochMillis) || epochMillis <= 0) {
    return '-';
  }
  return new Date(epochMillis).toLocaleString();
}

function formatBytes(sizeBytes: number): string {
  if (!Number.isFinite(sizeBytes) || sizeBytes < 0) {
    return '-';
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(2)} MB`;
}

function buildEntryKey(kind: string, fileName: string): string {
  return `${kind}:${fileName}`;
}

function readInitialSelection(): InitialSelection {
  if (typeof window === 'undefined') {
    return null;
  }
  const params = new URLSearchParams(window.location.search);
  const kind = params.get('kind');
  const fileName = params.get('name');
  if (!kind || !fileName) {
    return null;
  }
  return {
    kind,
    fileName,
  };
}

function parseRecordingBundle(entry: StorageEntryPayload | null): RecordingBundle | null {
  if (!entry || entry.kind !== 'recording') {
    return null;
  }
  try {
    const parsed = JSON.parse(entry.textContent) as Partial<RecordingBundle>;
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

export default function App() {
  const initialSelection = readInitialSelection();
  const [bridgePayload, setBridgePayload] = useState<BridgePingPayload | null>(null);
  const [bridgeError, setBridgeError] = useState<string>('');
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [storageIndex, setStorageIndex] = useState<StorageIndexPayload | null>(null);
  const [storageError, setStorageError] = useState<string>('');
  const [storageLoading, setStorageLoading] = useState(true);
  const [selectedEntryKey, setSelectedEntryKey] = useState<string>('');
  const [selectedEntry, setSelectedEntry] = useState<StorageEntryPayload | null>(null);
  const [entryError, setEntryError] = useState<string>('');
  const [entryLoading, setEntryLoading] = useState(false);
  const [selectedRecordingNodeKey, setSelectedRecordingNodeKey] = useState<string>('');

  async function loadBridgeStatus() {
    try {
      setBridgeLoading(true);
      const response = await fetch('./api/ping', {
        cache: 'no-store',
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const payload = (await response.json()) as BridgePingPayload;
      setBridgePayload(payload);
      setBridgeError('');
    } catch (error) {
      setBridgeError(error instanceof Error ? error.message : 'unknown error');
    } finally {
      setBridgeLoading(false);
    }
  }

  async function loadStorageEntry(kind: string, fileName: string) {
    try {
      setEntryLoading(true);
      setSelectedEntryKey(buildEntryKey(kind, fileName));
      const response = await fetch(
        `./api/storage/entry?kind=${encodeURIComponent(kind)}&name=${encodeURIComponent(fileName)}`,
        {
          cache: 'no-store',
        },
      );
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const payload = (await response.json()) as StorageEntryPayload;
      setSelectedEntry(payload);
      setEntryError('');
    } catch (error) {
      setEntryError(error instanceof Error ? error.message : 'unknown error');
      setSelectedEntry(null);
    } finally {
      setEntryLoading(false);
    }
  }

  async function loadStorageIndex() {
    try {
      setStorageLoading(true);
      const response = await fetch('./api/storage/index', {
        cache: 'no-store',
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const payload = (await response.json()) as StorageIndexPayload;
      setStorageIndex(payload);
      setStorageError('');

      const hasSelectedEntry = payload.categories.some((category) =>
        category.entries.some((entry) => buildEntryKey(entry.kind, entry.fileName) === selectedEntryKey),
      );
      if (selectedEntry && hasSelectedEntry) {
        void loadStorageEntry(selectedEntry.kind, selectedEntry.fileName);
        return;
      }

      if (initialSelection) {
        const targetEntry = payload.categories
          .flatMap((category) => category.entries)
          .find(
            (entry) =>
              entry.kind === initialSelection.kind &&
              entry.fileName === initialSelection.fileName,
          );
        if (targetEntry) {
          void loadStorageEntry(targetEntry.kind, targetEntry.fileName);
          return;
        }
      }

      const firstEntry = payload.categories.flatMap((category) => category.entries)[0];
      if (firstEntry) {
        void loadStorageEntry(firstEntry.kind, firstEntry.fileName);
        return;
      }
      setSelectedEntry(null);
      setSelectedEntryKey('');
      setEntryError('');
    } catch (error) {
      setStorageError(error instanceof Error ? error.message : 'unknown error');
      setStorageIndex(null);
    } finally {
      setStorageLoading(false);
    }
  }

  useEffect(() => {
    void loadBridgeStatus();
    void loadStorageIndex();
  }, []);

  useEffect(() => {
    const recordingBundle = parseRecordingBundle(selectedEntry);
    if (!recordingBundle) {
      setSelectedRecordingNodeKey('');
      return;
    }
    if (
      recordingBundle.nodes.some((node) => node.nodeKey === selectedRecordingNodeKey)
    ) {
      return;
    }
    setSelectedRecordingNodeKey(recordingBundle.nodes[0]?.nodeKey ?? '');
  }, [selectedEntry]);

  const bridgeStateLabel = bridgeLoading ? '连接中' : bridgeError ? '未连接' : '已连接';
  const bridgeStateClassName = bridgeLoading
    ? 'status-pill is-waiting'
    : bridgeError
      ? 'status-pill is-error'
      : 'status-pill is-ready';
  const totalEntryCount =
    storageIndex?.categories.reduce((total, category) => total + category.entryCount, 0) ?? 0;

  const recordingBundle = parseRecordingBundle(selectedEntry);
  const selectedRecordingNode = recordingBundle?.nodes.find(
    (node) => node.nodeKey === selectedRecordingNodeKey,
  );
  const selectedRecordingSeries =
    recordingBundle?.series.find((series) => series.nodeKey === selectedRecordingNodeKey) ?? null;

  return (
    <main className="app-shell">
      <section className="hero-panel">
        <div className="hero-copy">
          <p className="eyebrow">Embedded Offline Tooling</p>
          <h1>RedstoneLink Web Tools</h1>
          <p className="hero-text">
            当前页面处于网页前端主处理架构的 <strong>P2</strong>。
            这里已经能读取本地 recording bundle，并支持从游戏内录制流程直接跳转到对应结果页。
          </p>
        </div>
        <div className="hero-orbit" aria-hidden="true">
          <div className="orbit-core" />
          <div className="orbit-ring orbit-ring-a" />
          <div className="orbit-ring orbit-ring-b" />
        </div>
      </section>

      <section className="card-grid">
        <article className="info-card">
          <header className="card-header">
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
            <h2>本地 Bridge 状态</h2>
          </header>
          <dl className="meta-grid">
            <div>
              <dt>Mode</dt>
              <dd>{bridgePayload?.mode ?? '-'}</dd>
            </div>
            <div>
              <dt>Bridge Version</dt>
              <dd>{bridgePayload?.bridgeVersion ?? '-'}</dd>
            </div>
            <div>
              <dt>Mod ID</dt>
              <dd>{bridgePayload?.modId ?? '-'}</dd>
            </div>
            <div>
              <dt>Started At</dt>
              <dd>{bridgePayload ? formatStartedAt(bridgePayload.startedAtEpochMillis) : '-'}</dd>
            </div>
            <div className="meta-grid-full">
              <dt>Base URL</dt>
              <dd>{bridgePayload?.baseUrl ?? '-'}</dd>
            </div>
          </dl>
          {bridgeError ? <p className="error-text">无法读取 `./api/ping`：{bridgeError}</p> : null}
        </article>

        <article className="info-card">
          <header className="card-header">
            <span className="section-tag">Storage</span>
            <h2>本地资产仓概览</h2>
          </header>
          <dl className="meta-grid">
            <div>
              <dt>Root Path</dt>
              <dd>{storageIndex?.rootPath ?? '-'}</dd>
            </div>
            <div>
              <dt>Total Entries</dt>
              <dd>{storageLoading ? '-' : totalEntryCount}</dd>
            </div>
            <div>
              <dt>Categories</dt>
              <dd>{storageIndex?.categories.length ?? '-'}</dd>
            </div>
            <div>
              <dt>Refreshed At</dt>
              <dd>{storageIndex ? formatStartedAt(storageIndex.refreshedAtEpochMillis) : '-'}</dd>
            </div>
          </dl>
          <div className="toolbar-row">
            <button type="button" className="action-button" onClick={() => void loadStorageIndex()}>
              刷新索引
            </button>
          </div>
          {storageError ? (
            <p className="error-text">无法读取 `./api/storage/index`：{storageError}</p>
          ) : null}
        </article>

        <article className="info-card">
          <header className="card-header">
            <span className="section-tag">Next</span>
            <h2>P2 当前边界</h2>
          </header>
          <ul className="feature-list">
            <li>已打通状态面板录制 GUI 到本地 recording bundle 的结果导出链路</li>
            <li>网页端当前聚焦录制结果查看，不包含 P3 拓扑图分析器</li>
            <li>录制结果支持通过 `kind=recording&name=...` 直接定位</li>
            <li>P3 将接 serial 模式只读网络分析器</li>
          </ul>
        </article>
      </section>

      <section className="workspace-grid">
        <article className="info-card asset-panel">
          <header className="card-header">
            <span className="section-tag">Index</span>
            <h2>本地资产目录</h2>
          </header>
          {storageLoading ? <p className="empty-state">正在扫描本地资产仓...</p> : null}
          {!storageLoading && !storageIndex ? (
            <p className="empty-state">当前没有可读取的资产索引。</p>
          ) : null}
          {storageIndex?.categories.map((category) => (
            <section key={category.kind} className="asset-group">
              <div className="asset-group-header">
                <div>
                  <h3>{category.label}</h3>
                  <p className="asset-caption">
                    {category.directoryName} · {category.fileExtension}
                  </p>
                </div>
                <span className="status-pill is-ready">{category.entryCount}</span>
              </div>
              {category.entries.length === 0 ? (
                <p className="empty-state">当前目录暂无资产。</p>
              ) : (
                <ul className="asset-list">
                  {category.entries.map((entry) => {
                    const isSelected =
                      selectedEntryKey === buildEntryKey(entry.kind, entry.fileName);
                    return (
                      <li key={buildEntryKey(entry.kind, entry.fileName)}>
                        <button
                          type="button"
                          className={`asset-button${isSelected ? ' is-selected' : ''}`}
                          onClick={() => void loadStorageEntry(entry.kind, entry.fileName)}
                        >
                          <span className="asset-name">{entry.fileName}</span>
                          <span className="asset-meta">
                            {formatBytes(entry.sizeBytes)} ·{' '}
                            {formatStartedAt(entry.lastModifiedEpochMillis)}
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          ))}
        </article>

        <article className="info-card preview-panel">
          <header className="card-header">
            <span className="section-tag">
              {recordingBundle ? 'Recording Viewer' : 'Readonly Preview'}
            </span>
            <h2>{recordingBundle ? '录制结果查看' : '资产内容预览'}</h2>
          </header>
          {entryLoading ? <p className="empty-state">正在读取资产内容...</p> : null}
          {entryError ? <p className="error-text">无法读取当前资产：{entryError}</p> : null}
          {!entryLoading && !entryError && !selectedEntry ? (
            <p className="empty-state">请从左侧选择一个资产条目。</p>
          ) : null}
          {selectedEntry ? (
            <>
              <dl className="preview-meta">
                <div>
                  <dt>Kind</dt>
                  <dd>{selectedEntry.kind}</dd>
                </div>
                <div>
                  <dt>Label</dt>
                  <dd>{selectedEntry.label}</dd>
                </div>
                <div>
                  <dt>Relative Path</dt>
                  <dd>{selectedEntry.relativePath}</dd>
                </div>
                <div>
                  <dt>Encoding</dt>
                  <dd>{selectedEntry.contentEncoding}</dd>
                </div>
                <div>
                  <dt>File Size</dt>
                  <dd>{formatBytes(selectedEntry.sizeBytes)}</dd>
                </div>
                <div>
                  <dt>Last Modified</dt>
                  <dd>{formatStartedAt(selectedEntry.lastModifiedEpochMillis)}</dd>
                </div>
              </dl>

              {recordingBundle ? (
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
                        <h3>录制节点</h3>
                      </div>
                      {recordingBundle.nodes.map((node) => {
                        const isSelected = node.nodeKey === selectedRecordingNodeKey;
                        return (
                          <button
                            key={node.nodeKey}
                            type="button"
                            className={`recording-node-button${isSelected ? ' is-selected' : ''}`}
                            onClick={() => setSelectedRecordingNodeKey(node.nodeKey)}
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
                        <h3>{selectedRecordingNode?.displayText ?? '节点样本'}</h3>
                      </div>
                      {selectedRecordingNode ? (
                        <dl className="recording-meta-grid">
                          <div>
                            <dt>Node Key</dt>
                            <dd>{selectedRecordingNode.nodeKey}</dd>
                          </div>
                          <div>
                            <dt>Online</dt>
                            <dd>{selectedRecordingNode.online ? 'yes' : 'no'}</dd>
                          </div>
                          <div>
                            <dt>Allocated</dt>
                            <dd>{selectedRecordingNode.allocated ? 'yes' : 'no'}</dd>
                          </div>
                          <div>
                            <dt>Retired</dt>
                            <dd>{selectedRecordingNode.retired ? 'yes' : 'no'}</dd>
                          </div>
                        </dl>
                      ) : null}
                      {!selectedRecordingSeries ? (
                        <p className="empty-state">当前节点暂无样本。</p>
                      ) : (
                        <div className="sample-table-wrap">
                          <table className="sample-table">
                            <thead>
                              <tr>
                                <th>Tick</th>
                                <th>Online</th>
                                <th>Active</th>
                                <th>Input</th>
                                <th>Output</th>
                              </tr>
                            </thead>
                            <tbody>
                              {selectedRecordingSeries.samples.map((sample) => (
                                <tr key={`${selectedRecordingSeries.nodeKey}-${sample.tick}`}>
                                  <td>{sample.tick}</td>
                                  <td>{sample.online ? 'yes' : 'no'}</td>
                                  <td>{sample.active ? 'yes' : 'no'}</td>
                                  <td>{sample.inputPower}</td>
                                  <td>{sample.outputPower}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  </section>
                </section>
              ) : null}

              <details className="raw-preview-panel" open={!recordingBundle}>
                <summary>{recordingBundle ? '原始 JSON' : '资产文本内容'}</summary>
                <pre className="code-block">{selectedEntry.textContent}</pre>
              </details>
            </>
          ) : null}
        </article>
      </section>
    </main>
  );
}
