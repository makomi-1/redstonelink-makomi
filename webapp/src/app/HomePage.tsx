import { formatBytes, formatStartedAt } from './format';
import { buildEntryKey } from './location';
import type {
  BridgePingPayload,
  StorageEntryPayload,
  StorageEntrySummary,
  StorageIndexPayload,
} from './types';

type HomePageProps = {
  allEntries: StorageEntrySummary[];
  bridgeError: string;
  bridgePayload: BridgePingPayload | null;
  bridgeStateClassName: string;
  bridgeStateLabel: string;
  homeEntryError: string;
  homeEntryLoading: boolean;
  homeSelectedEntry: StorageEntryPayload | null;
  homeSelectedEntryKey: string;
  onHomeEntryClick: (entry: StorageEntrySummary) => void;
  onRefreshStorageIndex: () => void;
  storageError: string;
  storageIndex: StorageIndexPayload | null;
  storageLoading: boolean;
  totalEntryCount: number;
};

export default function HomePage({
  bridgeError,
  bridgePayload,
  bridgeStateClassName,
  bridgeStateLabel,
  homeEntryError,
  homeEntryLoading,
  homeSelectedEntry,
  homeSelectedEntryKey,
  onHomeEntryClick,
  onRefreshStorageIndex,
  storageError,
  storageIndex,
  storageLoading,
  totalEntryCount,
}: HomePageProps) {
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
            <button type="button" className="action-button" onClick={onRefreshStorageIndex}>
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
                          onClick={() => onHomeEntryClick(entry)}
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
