import GraphViewer from "../components/GraphViewer";
import ThemeSwitcher from "../components/ThemeSwitcher";
import type { GraphSnapshotBundle } from "../graphTypes";
import { buildEntryKey } from "./location";
import { formatBytes, formatStartedAt } from "./format";
import type { WebThemeId } from "./theme";
import type { StorageEntryPayload, StorageEntrySummary } from "./types";

type GraphPageProps = {
  bridgeError: string;
  bridgeStateClassName: string;
  bridgeStateLabel: string;
  graphBundle: GraphSnapshotBundle | null;
  graphEntryError: string;
  graphEntryLoading: boolean;
  graphSelectedEntry: StorageEntryPayload | null;
  graphPageDirty: boolean;
  graphEntries: StorageEntrySummary[];
  onDirtyStateChange: (dirty: boolean) => void;
  onGraphFileChange: (fileName: string) => void;
  onRefreshStorageIndex: () => void;
  onThemeChange: (themeId: WebThemeId) => void;
  selectedFileName: string;
  storageError: string;
  storageLoading: boolean;
  themeId: WebThemeId;
};

export default function GraphPage({
  bridgeError,
  bridgeStateClassName,
  bridgeStateLabel,
  graphBundle,
  graphEntryError,
  graphEntryLoading,
  graphSelectedEntry,
  graphPageDirty,
  graphEntries,
  onDirtyStateChange,
  onGraphFileChange,
  onRefreshStorageIndex,
  onThemeChange,
  selectedFileName,
  storageError,
  storageLoading,
  themeId,
}: GraphPageProps) {
  return (
    <main
      className="app-shell graph-page-shell"
      data-theme={themeId}
    >
      <section className="recording-page-header info-card">
        <div className="recording-page-header-row">
          <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
        </div>
        <div className="recording-page-title-wrap">
          <p className="eyebrow">Dedicated Graph Analyzer</p>
          <h1>graph 拓扑分析</h1>
          <p className="hero-text">
            当前页面支持序号拓扑与频道拓扑两种显示模式。可直接加载指定 graph
            文件，也可以先在游戏里执行
            <code> /rlclient web graph </code>
            导出并自动打开最新快照。
          </p>
        </div>
        <div className="recording-file-toolbar">
          <label className="recording-file-field">
            <span>加载 graph 文件</span>
            <select
              className="recording-file-select"
              value={selectedFileName}
              onChange={(event) => onGraphFileChange(event.target.value)}
            >
              <option value="">请选择本地 graph 文件</option>
              {graphEntries.map((entry) => (
                <option
                  key={buildEntryKey(entry.kind, entry.fileName)}
                  value={entry.fileName}
                >
                  {entry.fileName}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="action-button"
            onClick={onRefreshStorageIndex}
          >
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
              <dd>
                {formatStartedAt(graphSelectedEntry.lastModifiedEpochMillis)}
              </dd>
            </div>
          </dl>
        ) : null}
        {storageError ? (
          <p className="error-text">
            无法读取 `./api/storage/index`：{storageError}
          </p>
        ) : null}
        {bridgeError ? (
          <p className="error-text">无法读取 `./api/ping`：{bridgeError}</p>
        ) : null}
      </section>

      <section className="info-card theme-preview-panel theme-preview-panel-wide">
        <ThemeSwitcher
          currentThemeId={themeId}
          onThemeChange={onThemeChange}
        />
      </section>

      <section className="recording-page-main">
        {storageLoading ? (
          <p className="empty-state">正在扫描本地 graph 资产...</p>
        ) : null}
        {!storageLoading && graphEntries.length === 0 ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">当前本地资产仓还没有 graph 文件。</p>
          </article>
        ) : null}
        {!storageLoading &&
        graphEntries.length > 0 &&
        !selectedFileName &&
        !graphEntryLoading ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">
              请先在上方选择一个 graph 文件再开始查看拓扑。
            </p>
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
            <p className="error-text">
              当前文件不是合法的 graph snapshot bundle。
            </p>
          </article>
        ) : null}
        {graphBundle ? (
          <article className="info-card graph-viewer-card">
            <GraphViewer
              graphBundle={graphBundle}
              graphFileName={graphSelectedEntry?.fileName ?? ""}
              onDirtyStateChange={onDirtyStateChange}
            />
            <details className="raw-preview-panel">
              <summary>原始 JSON</summary>
              <pre className="code-block">
                {graphSelectedEntry?.textContent}
              </pre>
            </details>
          </article>
        ) : null}
        {!graphBundle && graphPageDirty ? null : null}
      </section>
    </main>
  );
}
