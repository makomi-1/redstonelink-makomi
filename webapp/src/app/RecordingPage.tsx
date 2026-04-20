import RecordingViewer from '../components/RecordingViewer';
import LanguageSwitcher from '../components/LanguageSwitcher';
import ThemeSwitcher from '../components/ThemeSwitcher';
import type { RecordingBundle } from '../recordingTypes';
import { buildEntryKey } from './location';
import { formatBytes, formatStartedAt } from './format';
import { pickLocalizedText, type AppLanguage } from './i18n';
import type { WebThemeId } from './theme';
import type { StorageEntryPayload, StorageEntrySummary } from './types';

type RecordingPageProps = {
  bridgeError: string;
  bridgeStateClassName: string;
  bridgeStateLabel: string;
  currentLanguage: AppLanguage;
  onLanguageChange: (language: AppLanguage) => void;
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
  currentLanguage,
  onLanguageChange,
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
  const text = (chineseText: string, englishText: string) =>
    pickLocalizedText(currentLanguage, chineseText, englishText);

  return (
    <main
      className="app-shell recording-page-shell"
      data-theme={themeId}
    >
      <section className="recording-page-header info-card">
        <div className="graph-page-header-top">
          <div className="recording-page-title-wrap graph-page-title-wrap">
            <h1>{text('录制曲线查看', 'Recording Viewer')}</h1>
            <p className="hero-text">
              {text(
                '当前页面只负责 recording bundle 主查看。可直接加载指定 recording 文件，并使用滚轮、拖拽和快捷键操作时间窗。',
                'This page is dedicated to recording bundle inspection. Load a recording file directly and use the mouse wheel, drag, and keyboard shortcuts to control the time window.',
              )}
            </p>
          </div>
          <div className="graph-page-header-meta">
            <span className={bridgeStateClassName}>{bridgeStateLabel}</span>
            <p className="eyebrow">Dedicated Recording Viewer</p>
          </div>
        </div>
        <div className="recording-file-toolbar">
          <label className="recording-file-field">
            <span>{text('加载 recording 文件', 'Load recording file')}</span>
            <select
              className="recording-file-select"
              value={selectedFileName}
              onChange={(event) => onRecordingFileChange(event.target.value)}
            >
              <option value="">{text('请选择本地 recording 文件', 'Select a local recording file')}</option>
              {recordingEntries.map((entry) => (
                <option key={buildEntryKey(entry.kind, entry.fileName)} value={entry.fileName}>
                  {entry.fileName}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="action-button" onClick={onRefreshStorageIndex}>
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
        {storageLoading ? <p className="empty-state">{text('正在扫描本地 recording 资产...', 'Scanning local recording assets...')}</p> : null}
        {!storageLoading && recordingEntries.length === 0 ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">{text('当前本地资产仓还没有 recording 文件。', 'There are no local recording files yet.')}</p>
          </article>
        ) : null}
        {!storageLoading && recordingEntries.length > 0 && !selectedFileName && !recordingEntryLoading ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">{text('请先在上方选择一个 recording 文件再开始查看曲线。', 'Select a recording file above before viewing the chart.')}</p>
          </article>
        ) : null}
        {recordingEntryLoading ? (
          <article className="info-card recording-empty-card">
            <p className="empty-state">{text('正在读取 recording 文件...', 'Loading recording file...')}</p>
          </article>
        ) : null}
        {recordingEntryError ? (
          <article className="info-card recording-empty-card">
            <p className="error-text">
              {text('无法读取当前 recording：', 'Unable to load the current recording: ')}
              {recordingEntryError}
            </p>
          </article>
        ) : null}
        {recordingSelectedEntry && !recordingBundle && !recordingEntryError ? (
          <article className="info-card recording-empty-card">
            <p className="error-text">{text('当前文件不是合法的 recording bundle。', 'The current file is not a valid recording bundle.')}</p>
          </article>
        ) : null}
        {recordingBundle ? (
          <article className="info-card recording-viewer-card">
            <RecordingViewer
              language={currentLanguage}
              recordingBundle={recordingBundle}
              themeId={themeId}
            />
          </article>
        ) : null}
      </section>
    </main>
  );
}
