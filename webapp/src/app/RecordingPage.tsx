import RecordingViewer from '../components/RecordingViewer';
import ThemeSwitcher from '../components/ThemeSwitcher';
import type { RecordingBundle } from '../recordingTypes';
import { buildEntryKey } from './location';
import { formatBytes, formatStartedAt } from './format';
import type { WebThemeId } from './theme';
import type { StorageEntryPayload, StorageEntrySummary } from './types';

type RecordingPageProps = {
  bridgeError: string;
  bridgeStateClassName: string;
  bridgeStateLabel: string;
  onThemeChange: (themeId: WebThemeId) => void;
  onRecordingFileChange: (fileName: string) => void;
  onRefreshStorageIndex: () => void;
  recordingBundle: RecordingBundle | null;
  recordingEntries: StorageEntrySummary[];
  recordingEntryError: string;
  recordingEntryLoading: boolean;
  recordingSelectedEntry: StorageEntryPayload | null;
  selectedFileName: string;
  storageError: string;
  storageLoading: boolean;
  themeId: WebThemeId;
};

export default function RecordingPage({
  bridgeError,
  bridgeStateClassName,
  bridgeStateLabel,
  onThemeChange,
  onRecordingFileChange,
  onRefreshStorageIndex,
  recordingBundle,
  recordingEntries,
  recordingEntryError,
  recordingEntryLoading,
  recordingSelectedEntry,
  selectedFileName,
  storageError,
  storageLoading,
  themeId,
}: RecordingPageProps) {
  return (
    <main
      className="app-shell recording-page-shell"
      data-theme={themeId}
    >
      <section className="recording-page-header info-card">
        <div className="graph-page-header-top">
          <div className="recording-page-title-wrap graph-page-title-wrap">
            <h1>录制曲线查看</h1>
            <p className="hero-text">
              当前页面只负责 recording bundle 主查看。可直接加载指定 recording 文件，并使用滚轮、拖拽和快捷键操作时间窗。
            </p>
          </div>
          <div className="graph-page-header-meta">
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
            <p className="eyebrow">Dedicated Recording Viewer</p>
          </div>
        </div>
        <div className="recording-file-toolbar">
          <label className="recording-file-field">
            <span>加载 recording 文件</span>
            <select
              className="recording-file-select"
              value={selectedFileName}
              onChange={(event) => onRecordingFileChange(event.target.value)}
            >
              <option value="">请选择本地 recording 文件</option>
              {recordingEntries.map((entry) => (
                <option key={buildEntryKey(entry.kind, entry.fileName)} value={entry.fileName}>
                  {entry.fileName}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="action-button" onClick={onRefreshStorageIndex}>
            刷新索引
          </button>
          <ThemeSwitcher
            currentThemeId={themeId}
            onThemeChange={onThemeChange}
          />
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
        {!storageLoading && recordingEntries.length > 0 && !selectedFileName && !recordingEntryLoading ? (
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
            <RecordingViewer
              recordingBundle={recordingBundle}
              themeId={themeId}
            />
          </article>
        ) : null}
      </section>
    </main>
  );
}
