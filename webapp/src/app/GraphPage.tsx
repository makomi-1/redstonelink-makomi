import GraphViewer from "../components/GraphViewer";
import LanguageSwitcher from "../components/LanguageSwitcher";
import ThemeSwitcher from "../components/ThemeSwitcher";
import type { GraphSnapshotBundle } from "../graphTypes";
import { buildEntryKey } from "./location";
import { formatBytes, formatStartedAt } from "./format";
import { pickLocalizedText, type AppLanguage } from "./i18n";
import type { WebThemeId } from "./theme";
import type { StorageEntryPayload, StorageEntrySummary } from "./types";

type GraphPageProps = {
  bridgeError: string;
  bridgeStateClassName: string;
  bridgeStateLabel: string;
  currentLanguage: AppLanguage;
  graphBundle: GraphSnapshotBundle | null;
  graphEntryError: string;
  graphEntryLoading: boolean;
  graphSelectedEntry: StorageEntryPayload | null;
  graphPageDirty: boolean;
  graphEntries: StorageEntrySummary[];
  onDirtyStateChange: (dirty: boolean) => void;
  onGraphFileChange: (fileName: string) => void;
  onGraphEntryReloaded?: (entry: StorageEntryPayload) => void;
  onLanguageChange: (language: AppLanguage) => void;
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
  currentLanguage,
  graphBundle,
  graphEntryError,
  graphEntryLoading,
  graphSelectedEntry,
  graphPageDirty,
  graphEntries,
  onDirtyStateChange,
  onGraphFileChange,
  onGraphEntryReloaded,
  onLanguageChange,
  onRefreshStorageIndex,
  onThemeChange,
  selectedFileName,
  storageError,
  storageLoading,
  themeId,
}: GraphPageProps) {
  const text = (chineseText: string, englishText: string) =>
    pickLocalizedText(currentLanguage, chineseText, englishText);

  return (
    <main
      className="app-shell graph-page-shell"
      data-theme={themeId}
    >
      <section className="recording-page-header info-card">
        <div className="graph-page-header-top">
          <div className="recording-page-title-wrap graph-page-title-wrap">
            <h1>{text('graph 拓扑分析', 'Graph Topology Analyzer')}</h1>
            <p className="hero-text">
              {text(
                '当前页面支持序号拓扑与频道拓扑两种显示模式。可直接加载指定 graph 文件，也可以先在游戏里执行',
                'This page supports both serial topology and channel topology views. You can load a graph file directly, or run',
              )}
              <code> /rlclient web graph </code>
              {text('导出并自动打开最新快照。', 'in game to export and open the latest snapshot automatically.')}
            </p>
          </div>
          <div className="graph-page-header-meta">
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
            <p className="eyebrow">Dedicated Graph Analyzer</p>
          </div>
        </div>
        <div className="recording-file-toolbar">
          <label className="recording-file-field">
            <span>{text('加载 graph 文件', 'Load graph file')}</span>
            <select
              className="recording-file-select"
              value={selectedFileName}
              onChange={(event) => onGraphFileChange(event.target.value)}
            >
              <option value="">{text('请选择本地 graph 文件', 'Select a local graph file')}</option>
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
            {text('刷新索引', 'Refresh index')}
          </button>
          <LanguageSwitcher
            currentLanguage={currentLanguage}
            onLanguageChange={onLanguageChange}
          />
          <ThemeSwitcher
            currentThemeId={themeId}
            language={currentLanguage}
            onThemeChange={onThemeChange}
          />
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
            {text('无法读取 `./api/storage/index`：', 'Unable to load `./api/storage/index`: ')}
            {storageError}
          </p>
        ) : null}
        {bridgeError ? (
          <p className="error-text">
            {text('无法读取 `./api/ping`：', 'Unable to load `./api/ping`: ')}
            {bridgeError}
          </p>
        ) : null}
      </section>

      <section className="recording-page-main">
        {storageLoading ? (
          <p className="empty-state">{text('正在扫描本地 graph 资产...', 'Scanning local graph assets...')}</p>
        ) : null}
        {!storageLoading && graphEntries.length === 0 ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">{text('当前本地资产仓还没有 graph 文件。', 'There are no local graph files yet.')}</p>
          </article>
        ) : null}
        {!storageLoading &&
        graphEntries.length > 0 &&
        !selectedFileName &&
        !graphEntryLoading ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">
              {text('请先在上方选择一个 graph 文件再开始查看拓扑。', 'Select a graph file above before viewing topology.')}
            </p>
          </article>
        ) : null}
        {graphEntryLoading ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">{text('正在读取 graph 文件...', 'Loading graph file...')}</p>
          </article>
        ) : null}
        {graphEntryError ? (
          <article className="info-card recording-empty-card">
            <p className="error-text">
              {text('无法读取当前 graph：', 'Unable to load the current graph: ')}
              {graphEntryError}
            </p>
          </article>
        ) : null}
        {graphSelectedEntry && !graphBundle && !graphEntryError ? (
          <article className="info-card recording-empty-card">
            <p className="error-text">
              {text('当前文件不是合法的 graph snapshot bundle。', 'The current file is not a valid graph snapshot bundle.')}
            </p>
          </article>
        ) : null}
        {graphBundle ? (
          <article className="info-card graph-viewer-card">
            <GraphViewer
              graphBundle={graphBundle}
              graphFileName={graphSelectedEntry?.fileName ?? ""}
              language={currentLanguage}
              onDirtyStateChange={onDirtyStateChange}
              onGraphEntryReloaded={onGraphEntryReloaded}
            />
          </article>
        ) : null}
        {!graphBundle && graphPageDirty ? null : null}
      </section>
    </main>
  );
}
