import { useEffect, useMemo, useState } from 'react';
import GraphViewer from './components/GraphViewer';
import RecordingViewer from './components/RecordingViewer';
import { parseGraphSnapshotBundle } from './graphTypes';
import { parseRecordingBundle } from './recordingTypes';

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

type AppPage = 'home' | 'recording' | 'graph';

type AppLocation = {
  page: AppPage;
  kind: string;
  fileName: string;
};

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

function parseAppLocation(): AppLocation {
  if (typeof window === 'undefined') {
    return {
      page: 'home',
      kind: '',
      fileName: '',
    };
  }
  const params = new URLSearchParams(window.location.search);
  const pageParam = params.get('page');
  const page: AppPage = pageParam === 'recording' || pageParam === 'graph' ? pageParam : 'home';
  const fileName = params.get('name') ?? '';
  const kind =
    params.get('kind') ??
    (page === 'recording' && fileName
      ? 'recording'
      : page === 'graph' && fileName
        ? 'graph'
        : '');
  return {
    page,
    kind,
    fileName,
  };
}

function buildAppHref(location: AppLocation): string {
  const params = new URLSearchParams();
  if (location.page !== 'home') {
    params.set('page', location.page);
  }
  if (location.kind) {
    params.set('kind', location.kind);
  }
  if (location.fileName) {
    params.set('name', location.fileName);
  }
  const search = params.toString();
  return search ? `./?${search}` : './';
}

function findStorageEntry(
  storageIndex: StorageIndexPayload | null,
  kind: string,
  fileName: string,
): StorageEntrySummary | null {
  if (!storageIndex || !kind || !fileName) {
    return null;
  }
  return (
    storageIndex.categories
      .flatMap((category) => category.entries)
      .find((entry) => entry.kind === kind && entry.fileName === fileName) ?? null
  );
}

/**
 * 网页主入口。
 * <p>
 * 当前入口承载三个页面：
 * </p>
 * <ul>
 * <li>首页：本地资产入口与只读预览</li>
 * <li>recording 页：独立录制曲线查看</li>
 * <li>graph 页：独立 serial 拓扑分析</li>
 * </ul>
 */
export default function App() {
  const [appLocation, setAppLocation] = useState<AppLocation>(() => parseAppLocation());
  const [graphPageDirty, setGraphPageDirty] = useState(false);
  const [bridgePayload, setBridgePayload] = useState<BridgePingPayload | null>(null);
  const [bridgeError, setBridgeError] = useState<string>('');
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [storageIndex, setStorageIndex] = useState<StorageIndexPayload | null>(null);
  const [storageError, setStorageError] = useState<string>('');
  const [storageLoading, setStorageLoading] = useState(true);
  const [homeSelectedEntryKey, setHomeSelectedEntryKey] = useState<string>('');
  const [homeSelectedEntry, setHomeSelectedEntry] = useState<StorageEntryPayload | null>(null);
  const [homeEntryError, setHomeEntryError] = useState<string>('');
  const [homeEntryLoading, setHomeEntryLoading] = useState(false);
  const [recordingSelectedEntryKey, setRecordingSelectedEntryKey] = useState<string>('');
  const [recordingSelectedEntry, setRecordingSelectedEntry] =
    useState<StorageEntryPayload | null>(null);
  const [recordingEntryError, setRecordingEntryError] = useState<string>('');
  const [recordingEntryLoading, setRecordingEntryLoading] = useState(false);
  const [graphSelectedEntryKey, setGraphSelectedEntryKey] = useState<string>('');
  const [graphSelectedEntry, setGraphSelectedEntry] = useState<StorageEntryPayload | null>(null);
  const [graphEntryError, setGraphEntryError] = useState<string>('');
  const [graphEntryLoading, setGraphEntryLoading] = useState(false);

  const totalEntryCount =
    storageIndex?.categories.reduce((total, category) => total + category.entryCount, 0) ?? 0;
  const bridgeStateLabel = bridgeLoading ? '连接中' : bridgeError ? '未连接' : '已连接';
  const bridgeStateClassName = bridgeLoading
    ? 'status-pill is-waiting'
    : bridgeError
      ? 'status-pill is-error'
      : 'status-pill is-ready';
  const allEntries = useMemo(
    () => storageIndex?.categories.flatMap((category) => category.entries) ?? [],
    [storageIndex],
  );
  const recordingEntries = useMemo(
    () =>
      storageIndex?.categories.find((category) => category.kind === 'recording')?.entries ?? [],
    [storageIndex],
  );
  const graphEntries = useMemo(
    () => storageIndex?.categories.find((category) => category.kind === 'graph')?.entries ?? [],
    [storageIndex],
  );
  const recordingBundle =
    recordingSelectedEntry == null
      ? null
      : parseRecordingBundle(recordingSelectedEntry.textContent, recordingSelectedEntry.kind);
  const graphBundle = useMemo(
    () =>
      graphSelectedEntry == null
        ? null
        : parseGraphSnapshotBundle(graphSelectedEntry.textContent, graphSelectedEntry.kind),
    [graphSelectedEntry?.kind, graphSelectedEntry?.textContent],
  );

  useEffect(() => {
    const handlePopState = () => {
      setAppLocation(parseAppLocation());
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('popstate', handlePopState);
    }
    return () => {
      if (typeof window !== 'undefined') {
        window.removeEventListener('popstate', handlePopState);
      }
    };
  }, []);

  useEffect(() => {
    void loadBridgeStatus();
    void loadStorageIndex();
  }, []);

  useEffect(() => {
    if (appLocation.page !== 'graph') {
      setGraphPageDirty(false);
    }
  }, [appLocation.page]);

  useEffect(() => {
    if (!storageIndex || appLocation.page !== 'home') {
      return;
    }
    const targetEntry =
      appLocation.kind && appLocation.fileName
        ? findStorageEntry(storageIndex, appLocation.kind, appLocation.fileName)
        : null;
    if (targetEntry) {
      const targetKey = buildEntryKey(targetEntry.kind, targetEntry.fileName);
      if (targetKey !== homeSelectedEntryKey) {
        void loadHomeEntry(targetEntry.kind, targetEntry.fileName);
      }
      return;
    }
    if (!homeSelectedEntryKey) {
      const firstEntry = allEntries[0];
      if (firstEntry) {
        void loadHomeEntry(firstEntry.kind, firstEntry.fileName);
      }
    }
  }, [allEntries, appLocation, homeSelectedEntryKey, storageIndex]);

  useEffect(() => {
    if (!storageIndex || appLocation.page !== 'recording') {
      return;
    }
    if (appLocation.kind === 'recording' && appLocation.fileName) {
      const targetEntry = findStorageEntry(storageIndex, 'recording', appLocation.fileName);
      if (!targetEntry) {
        setRecordingSelectedEntry(null);
        setRecordingSelectedEntryKey('');
        setRecordingEntryError(`未找到指定 recording 文件：${appLocation.fileName}`);
        return;
      }
      const targetKey = buildEntryKey(targetEntry.kind, targetEntry.fileName);
      if (targetKey !== recordingSelectedEntryKey) {
        void loadRecordingEntry(targetEntry.fileName);
      }
      return;
    }
    setRecordingSelectedEntry(null);
    setRecordingSelectedEntryKey('');
    setRecordingEntryError('');
  }, [appLocation, recordingSelectedEntryKey, storageIndex]);

  useEffect(() => {
    if (!storageIndex || appLocation.page !== 'graph') {
      return;
    }
    if (appLocation.kind === 'graph' && appLocation.fileName) {
      const targetEntry = findStorageEntry(storageIndex, 'graph', appLocation.fileName);
      if (!targetEntry) {
        setGraphSelectedEntry(null);
        setGraphSelectedEntryKey('');
        setGraphEntryError(`未找到指定 graph 文件：${appLocation.fileName}`);
        return;
      }
      const targetKey = buildEntryKey(targetEntry.kind, targetEntry.fileName);
      if (targetKey !== graphSelectedEntryKey) {
        void loadGraphEntry(targetEntry.fileName);
      }
      return;
    }
    setGraphSelectedEntry(null);
    setGraphSelectedEntryKey('');
    setGraphEntryError('');
  }, [appLocation, graphSelectedEntryKey, storageIndex]);

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
    } catch (error) {
      setStorageError(error instanceof Error ? error.message : 'unknown error');
      setStorageIndex(null);
    } finally {
      setStorageLoading(false);
    }
  }

  async function fetchStorageEntry(kind: string, fileName: string): Promise<StorageEntryPayload> {
    const response = await fetch(
      `./api/storage/entry?kind=${encodeURIComponent(kind)}&name=${encodeURIComponent(fileName)}`,
      {
        cache: 'no-store',
      },
    );
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return (await response.json()) as StorageEntryPayload;
  }

  async function loadHomeEntry(kind: string, fileName: string) {
    try {
      setHomeEntryLoading(true);
      setHomeEntryError('');
      setHomeSelectedEntryKey(buildEntryKey(kind, fileName));
      const payload = await fetchStorageEntry(kind, fileName);
      setHomeSelectedEntry(payload);
    } catch (error) {
      setHomeEntryError(error instanceof Error ? error.message : 'unknown error');
      setHomeSelectedEntry(null);
    } finally {
      setHomeEntryLoading(false);
    }
  }

  async function loadRecordingEntry(fileName: string) {
    try {
      setRecordingEntryLoading(true);
      setRecordingEntryError('');
      setRecordingSelectedEntryKey(buildEntryKey('recording', fileName));
      const payload = await fetchStorageEntry('recording', fileName);
      setRecordingSelectedEntry(payload);
    } catch (error) {
      setRecordingEntryError(error instanceof Error ? error.message : 'unknown error');
      setRecordingSelectedEntry(null);
    } finally {
      setRecordingEntryLoading(false);
    }
  }

  async function loadGraphEntry(fileName: string) {
    try {
      setGraphEntryLoading(true);
      setGraphEntryError('');
      setGraphSelectedEntryKey(buildEntryKey('graph', fileName));
      const payload = await fetchStorageEntry('graph', fileName);
      setGraphSelectedEntry(payload);
    } catch (error) {
      setGraphEntryError(error instanceof Error ? error.message : 'unknown error');
      setGraphSelectedEntry(null);
    } finally {
      setGraphEntryLoading(false);
    }
  }

  function syncAppLocation(nextLocation: AppLocation, mode: 'push' | 'replace') {
    if (typeof window !== 'undefined') {
      const nextHref = buildAppHref(nextLocation);
      if (mode === 'push') {
        window.history.pushState({}, '', nextHref);
      } else {
        window.history.replaceState({}, '', nextHref);
      }
    }
    setAppLocation(nextLocation);
  }

  function handleHomeEntryClick(entry: StorageEntrySummary) {
    if (entry.kind === 'recording') {
      syncAppLocation(
        {
          page: 'recording',
          kind: 'recording',
          fileName: entry.fileName,
        },
        'push',
      );
      return;
    }
    if (entry.kind === 'graph') {
      syncAppLocation(
        {
          page: 'graph',
          kind: 'graph',
          fileName: entry.fileName,
        },
        'push',
      );
      return;
    }
    syncAppLocation(
      {
        page: 'home',
        kind: entry.kind,
        fileName: entry.fileName,
      },
      'replace',
    );
  }

  function handleRecordingFileChange(fileName: string) {
    syncAppLocation(
      {
        page: 'recording',
        kind: fileName ? 'recording' : '',
        fileName,
      },
      'replace',
    );
  }

  function handleGraphFileChange(fileName: string) {
    if (graphPageDirty && typeof window !== 'undefined') {
      const confirmed = window.confirm('当前 graph 页面有未保存修改，切换文件会丢失本地草稿，是否继续？');
      if (!confirmed) {
        return;
      }
    }
    syncAppLocation(
      {
        page: 'graph',
        kind: fileName ? 'graph' : '',
        fileName,
      },
      'replace',
    );
  }

  function goHome() {
    if (appLocation.page === 'graph' && graphPageDirty && typeof window !== 'undefined') {
      const confirmed = window.confirm('当前 graph 页面有未保存修改，返回首页会丢失本地草稿，是否继续？');
      if (!confirmed) {
        return;
      }
    }
    syncAppLocation(
      {
        page: 'home',
        kind: '',
        fileName: '',
      },
      'push',
    );
  }

  if (appLocation.page === 'recording') {
    return (
      <main className="app-shell recording-page-shell">
        <section className="recording-page-header info-card">
          <div className="recording-page-header-row">
            <button type="button" className="action-button" onClick={goHome}>
              返回首页
            </button>
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
          </div>
          <div className="recording-page-title-wrap">
            <p className="eyebrow">Dedicated Recording Viewer</p>
            <h1>录制曲线查看</h1>
            <p className="hero-text">
              当前页面只负责 recording bundle 主查看。可直接加载指定 recording 文件，并使用滚轮、拖拽和快捷键操作时间窗。
            </p>
          </div>
          <div className="recording-file-toolbar">
            <label className="recording-file-field">
              <span>加载 recording 文件</span>
              <select
                className="recording-file-select"
                value={appLocation.page === 'recording' ? appLocation.fileName : ''}
                onChange={(event) => handleRecordingFileChange(event.target.value)}
              >
                <option value="">请选择本地 recording 文件</option>
                {recordingEntries.map((entry) => (
                  <option key={buildEntryKey(entry.kind, entry.fileName)} value={entry.fileName}>
                    {entry.fileName}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="action-button" onClick={() => void loadStorageIndex()}>
              刷新索引
            </button>
          </div>
          {recordingSelectedEntry ? (
            <dl className="recording-file-meta">
              <div>
                <dt>File</dt>
                <dd>{recordingSelectedEntry.fileName}</dd>
              </div>
              <div>
                <dt>Relative Path</dt>
                <dd>{recordingSelectedEntry.relativePath}</dd>
              </div>
              <div>
                <dt>File Size</dt>
                <dd>{formatBytes(recordingSelectedEntry.sizeBytes)}</dd>
              </div>
              <div>
                <dt>Last Modified</dt>
                <dd>{formatStartedAt(recordingSelectedEntry.lastModifiedEpochMillis)}</dd>
              </div>
            </dl>
          ) : null}
          {storageError ? (
            <p className="error-text">无法读取 `./api/storage/index`：{storageError}</p>
          ) : null}
          {bridgeError ? <p className="error-text">无法读取 `./api/ping`：{bridgeError}</p> : null}
        </section>

        <section className="recording-page-main">
          {storageLoading ? <p className="empty-state">正在扫描本地 recording 资产...</p> : null}
          {!storageLoading && recordingEntries.length === 0 ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">当前本地资产仓还没有 recording 文件。</p>
            </article>
          ) : null}
          {!storageLoading &&
          recordingEntries.length > 0 &&
          !appLocation.fileName &&
          !recordingEntryLoading ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">请先在上方选择一个 recording 文件再开始查看曲线。</p>
            </article>
          ) : null}
          {recordingEntryLoading ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">正在读取 recording 文件...</p>
            </article>
          ) : null}
          {recordingEntryError ? (
            <article className="info-card recording-empty-card">
              <p className="error-text">无法读取当前 recording：{recordingEntryError}</p>
            </article>
          ) : null}
          {recordingSelectedEntry && !recordingBundle && !recordingEntryError ? (
            <article className="info-card recording-empty-card">
              <p className="error-text">当前文件不是合法的 recording bundle。</p>
            </article>
          ) : null}
          {recordingBundle ? (
            <article className="info-card recording-viewer-card">
              <RecordingViewer recordingBundle={recordingBundle} />
              <details className="raw-preview-panel">
                <summary>原始 JSON</summary>
                <pre className="code-block">{recordingSelectedEntry?.textContent}</pre>
              </details>
            </article>
          ) : null}
        </section>
      </main>
    );
  }

  if (appLocation.page === 'graph') {
    return (
      <main className="app-shell graph-page-shell">
        <section className="recording-page-header info-card">
          <div className="recording-page-header-row">
            <button type="button" className="action-button" onClick={goHome}>
              返回首页
            </button>
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
          </div>
          <div className="recording-page-title-wrap">
            <p className="eyebrow">Dedicated Graph Analyzer</p>
            <h1>serial 拓扑分析</h1>
            <p className="hero-text">
              当前页面只负责 graph snapshot 主查看。可直接加载指定 graph 文件，也可以先在游戏里执行
              <code> /rlclient web graph </code>
              导出并自动打开最新快照。
            </p>
          </div>
          <div className="recording-file-toolbar">
            <label className="recording-file-field">
              <span>加载 graph 文件</span>
              <select
                className="recording-file-select"
                value={appLocation.page === 'graph' ? appLocation.fileName : ''}
                onChange={(event) => handleGraphFileChange(event.target.value)}
              >
                <option value="">请选择本地 graph 文件</option>
                {graphEntries.map((entry) => (
                  <option key={buildEntryKey(entry.kind, entry.fileName)} value={entry.fileName}>
                    {entry.fileName}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="action-button" onClick={() => void loadStorageIndex()}>
              刷新索引
            </button>
          </div>
          {graphSelectedEntry ? (
            <dl className="recording-file-meta">
              <div>
                <dt>File</dt>
                <dd>{graphSelectedEntry.fileName}</dd>
              </div>
              <div>
                <dt>Relative Path</dt>
                <dd>{graphSelectedEntry.relativePath}</dd>
              </div>
              <div>
                <dt>File Size</dt>
                <dd>{formatBytes(graphSelectedEntry.sizeBytes)}</dd>
              </div>
              <div>
                <dt>Last Modified</dt>
                <dd>{formatStartedAt(graphSelectedEntry.lastModifiedEpochMillis)}</dd>
              </div>
            </dl>
          ) : null}
          {storageError ? (
            <p className="error-text">无法读取 `./api/storage/index`：{storageError}</p>
          ) : null}
          {bridgeError ? <p className="error-text">无法读取 `./api/ping`：{bridgeError}</p> : null}
        </section>

        <section className="recording-page-main">
          {storageLoading ? <p className="empty-state">正在扫描本地 graph 资产...</p> : null}
          {!storageLoading && graphEntries.length === 0 ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">当前本地资产仓还没有 graph 文件。</p>
            </article>
          ) : null}
          {!storageLoading && graphEntries.length > 0 && !appLocation.fileName && !graphEntryLoading ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">请先在上方选择一个 graph 文件再开始查看拓扑。</p>
            </article>
          ) : null}
          {graphEntryLoading ? (
            <article className="info-card recording-empty-card">
              <p className="empty-state">正在读取 graph 文件...</p>
            </article>
          ) : null}
          {graphEntryError ? (
            <article className="info-card recording-empty-card">
              <p className="error-text">无法读取当前 graph：{graphEntryError}</p>
            </article>
          ) : null}
          {graphSelectedEntry && !graphBundle && !graphEntryError ? (
            <article className="info-card recording-empty-card">
              <p className="error-text">当前文件不是合法的 graph snapshot bundle。</p>
            </article>
          ) : null}
          {graphBundle ? (
            <article className="info-card graph-viewer-card">
              <GraphViewer
                graphBundle={graphBundle}
                graphFileName={graphSelectedEntry?.fileName ?? ''}
                onDirtyStateChange={setGraphPageDirty}
              />
              <details className="raw-preview-panel">
                <summary>原始 JSON</summary>
                <pre className="code-block">{graphSelectedEntry?.textContent}</pre>
              </details>
            </article>
          ) : null}
        </section>
      </main>
    );
  }

  return (
    <main className="app-shell">
      <section className="hero-panel">
        <div className="hero-copy">
          <p className="eyebrow">Embedded Offline Tooling</p>
          <h1>RedstoneLink Web Tools</h1>
          <p className="hero-text">
            首页继续作为离线资产入口；recording 和 graph 资产都会直接切换到独立页面主查看，避免把分析界面塞进小预览面板里。
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
            <span className="section-tag">Dedicated Views</span>
            <h2>独立主查看页</h2>
          </header>
          <ul className="feature-list">
            <li>recording 资产点击后直接切到独立曲线查看页</li>
            <li>graph 资产点击后直接切到独立拓扑分析页</li>
            <li>图页支持搜索、缩放、平移、重新布局与节点详情</li>
            <li>可先在游戏里执行 `/rlclient web graph` 导出当前可见 serial 图快照</li>
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
                      homeSelectedEntryKey === buildEntryKey(entry.kind, entry.fileName);
                    return (
                      <li key={buildEntryKey(entry.kind, entry.fileName)}>
                        <button
                          type="button"
                          className={`asset-button${isSelected ? ' is-selected' : ''}`}
                          onClick={() => handleHomeEntryClick(entry)}
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
            <span className="section-tag">Readonly Preview</span>
            <h2>资产内容预览</h2>
          </header>
          {homeEntryLoading ? <p className="empty-state">正在读取资产内容...</p> : null}
          {homeEntryError ? <p className="error-text">无法读取当前资产：{homeEntryError}</p> : null}
          {!homeEntryLoading && !homeEntryError && !homeSelectedEntry ? (
            <p className="empty-state">请从左侧选择一个资产条目。</p>
          ) : null}
          {homeSelectedEntry ? (
            <>
              <dl className="preview-meta">
                <div>
                  <dt>Kind</dt>
                  <dd>{homeSelectedEntry.kind}</dd>
                </div>
                <div>
                  <dt>Label</dt>
                  <dd>{homeSelectedEntry.label}</dd>
                </div>
                <div>
                  <dt>Relative Path</dt>
                  <dd>{homeSelectedEntry.relativePath}</dd>
                </div>
                <div>
                  <dt>Encoding</dt>
                  <dd>{homeSelectedEntry.contentEncoding}</dd>
                </div>
                <div>
                  <dt>File Size</dt>
                  <dd>{formatBytes(homeSelectedEntry.sizeBytes)}</dd>
                </div>
                <div>
                  <dt>Last Modified</dt>
                  <dd>{formatStartedAt(homeSelectedEntry.lastModifiedEpochMillis)}</dd>
                </div>
              </dl>

              {homeSelectedEntry.kind === 'recording' ? (
                <p className="empty-state">
                  recording 资产已迁移到独立曲线页查看。请从左侧再次点击对应 recording 文件进入专用页面。
                </p>
              ) : null}

              {homeSelectedEntry.kind === 'graph' ? (
                <p className="empty-state">
                  graph 资产已迁移到独立拓扑页查看。请从左侧再次点击对应 graph 文件进入专用页面。
                </p>
              ) : null}

              <details
                className="raw-preview-panel"
                open={homeSelectedEntry.kind !== 'recording' && homeSelectedEntry.kind !== 'graph'}
              >
                <summary>资产文本内容</summary>
                <pre className="code-block">{homeSelectedEntry.textContent}</pre>
              </details>
            </>
          ) : null}
        </article>
      </section>
    </main>
  );
}
