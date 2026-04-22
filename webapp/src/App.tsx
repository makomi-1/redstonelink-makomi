import { useEffect, useMemo, useRef, useState } from 'react';
import GraphPage from './app/GraphPage';
import RecordingPage from './app/RecordingPage';
import { buildAppHref, buildEntryKey, parseAppLocation } from './app/location';
import {
  fetchWebPreferences,
  saveWebPreferences,
} from './app/preferencesApi';
import {
  buildStorageEntrySummary,
  fetchBridgeStatus,
  fetchStorageEntry,
  fetchStorageIndex,
  findStorageEntry,
} from './app/storageApi';
import {
  DEFAULT_APP_LANGUAGE,
  isAppLanguage,
  pickLocalizedText,
  type AppLanguage,
} from './app/i18n';
import {
  DEFAULT_WEB_THEME_ID,
  isWebThemeId,
  type WebThemeId,
} from './app/theme';
import type {
  AppLocation,
  StorageEntryPayload,
  StorageEntrySummary,
  StorageIndexPayload,
} from './app/types';
import { parseGraphSnapshotBundle } from './graphTypes';
import { parseRecordingBundle } from './recordingTypes';

function compareStorageEntrySummary(
  left: StorageEntrySummary,
  right: StorageEntrySummary,
): number {
  if (left.lastModifiedEpochMillis !== right.lastModifiedEpochMillis) {
    return right.lastModifiedEpochMillis - left.lastModifiedEpochMillis;
  }
  return left.fileName.localeCompare(right.fileName);
}

function upsertStorageEntryIntoIndex(
  storageIndex: StorageIndexPayload | null,
  entry: StorageEntryPayload,
): StorageIndexPayload | null {
  if (storageIndex == null) {
    return storageIndex;
  }
  const entrySummary = buildStorageEntrySummary(entry);
  let graphCategoryFound = false;
  const nextCategories = storageIndex.categories.map((category) => {
    if (category.kind !== entry.kind) {
      return category;
    }
    graphCategoryFound = true;
    const nextEntries = [
      ...category.entries.filter(
        (currentEntry) =>
          !(
            currentEntry.kind === entrySummary.kind &&
            currentEntry.fileName === entrySummary.fileName
          ),
      ),
      entrySummary,
    ].sort(compareStorageEntrySummary);
    return {
      ...category,
      compressed: entry.compressed,
      entryCount: nextEntries.length,
      entries: nextEntries,
    };
  });
  if (!graphCategoryFound) {
    nextCategories.push({
      kind: entry.kind,
      label: entry.label,
      directoryName: entry.kind,
      fileExtension: entry.compressed ? '.json.gz' : '.json',
      compressed: entry.compressed,
      entryCount: 1,
      entries: [entrySummary],
    });
  }
  return {
    ...storageIndex,
    refreshedAtEpochMillis: Date.now(),
    categories: nextCategories,
  };
}

/**
 * 网页主入口。
 * <p>
 * 当前入口仅承载 graph 与 recording 两个独立主页面。
 * </p>
 */
export default function App() {
  const [appLocation, setAppLocation] = useState<AppLocation>(() => parseAppLocation());
  const [currentLanguage, setCurrentLanguage] =
    useState<AppLanguage>(DEFAULT_APP_LANGUAGE);
  const [currentThemeId, setCurrentThemeId] =
    useState<WebThemeId>(DEFAULT_WEB_THEME_ID);
  const [graphPageDirty, setGraphPageDirty] = useState(false);
  const [bridgeError, setBridgeError] = useState<string>('');
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [storageIndex, setStorageIndex] = useState<StorageIndexPayload | null>(null);
  const [storageError, setStorageError] = useState<string>('');
  const [storageLoading, setStorageLoading] = useState(true);
  const [recordingSelectedEntryKey, setRecordingSelectedEntryKey] = useState<string>('');
  const [recordingSelectedEntry, setRecordingSelectedEntry] =
    useState<StorageEntryPayload | null>(null);
  const [recordingEntryError, setRecordingEntryError] = useState<string>('');
  const [recordingEntryLoading, setRecordingEntryLoading] = useState(false);
  const [graphSelectedEntryKey, setGraphSelectedEntryKey] = useState<string>('');
  const [graphSelectedEntry, setGraphSelectedEntry] = useState<StorageEntryPayload | null>(null);
  const [graphEntryError, setGraphEntryError] = useState<string>('');
  const [graphEntryLoading, setGraphEntryLoading] = useState(false);
  const currentLanguageRef = useRef<AppLanguage>(DEFAULT_APP_LANGUAGE);
  const currentThemeIdRef = useRef<WebThemeId>(DEFAULT_WEB_THEME_ID);
  const preferencesSaveQueueRef = useRef<Promise<unknown>>(Promise.resolve());

  const bridgeStateLabel = bridgeLoading
    ? pickLocalizedText(currentLanguage, '连接中', 'Connecting')
    : bridgeError
      ? pickLocalizedText(currentLanguage, '未连接', 'Disconnected')
      : pickLocalizedText(currentLanguage, '已连接', 'Connected');
  const bridgeStateClassName = bridgeLoading
    ? 'status-pill is-waiting'
    : bridgeError
      ? 'status-pill is-error'
      : 'status-pill is-ready';
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
    void loadStorageIndexState();
    void loadPreferencesState();
  }, []);

  useEffect(() => {
    currentLanguageRef.current = currentLanguage;
  }, [currentLanguage]);

  useEffect(() => {
    currentThemeIdRef.current = currentThemeId;
  }, [currentThemeId]);

  useEffect(() => {
    if (appLocation.page !== 'graph') {
      setGraphPageDirty(false);
    }
  }, [appLocation.page]);

  useEffect(() => {
    if (!storageIndex || appLocation.page !== 'recording') {
      return;
    }
    if (appLocation.kind === 'recording' && appLocation.fileName) {
      const targetEntry = findStorageEntry(storageIndex, 'recording', appLocation.fileName);
      if (!targetEntry) {
        setRecordingSelectedEntry(null);
        setRecordingSelectedEntryKey('');
        setRecordingEntryError(
          pickLocalizedText(
            currentLanguage,
            `未找到指定 recording 文件：${appLocation.fileName}`,
            `Requested recording file was not found: ${appLocation.fileName}`,
          ),
        );
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
  }, [appLocation, currentLanguage, recordingSelectedEntryKey, storageIndex]);

  useEffect(() => {
    if (!storageIndex || appLocation.page !== 'graph') {
      return;
    }
    if (appLocation.kind === 'graph' && appLocation.fileName) {
      const targetEntry = findStorageEntry(storageIndex, 'graph', appLocation.fileName);
      if (!targetEntry) {
        setGraphSelectedEntry(null);
        setGraphSelectedEntryKey('');
        setGraphEntryError(
          pickLocalizedText(
            currentLanguage,
            `未找到指定 graph 文件：${appLocation.fileName}`,
            `Requested graph file was not found: ${appLocation.fileName}`,
          ),
        );
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
  }, [appLocation, currentLanguage, graphSelectedEntryKey, storageIndex]);

  async function loadBridgeStatus() {
    try {
      setBridgeLoading(true);
      await fetchBridgeStatus();
      setBridgeError('');
    } catch (error) {
      setBridgeError(error instanceof Error ? error.message : 'unknown error');
    } finally {
      setBridgeLoading(false);
    }
  }

  async function loadStorageIndexState() {
    try {
      setStorageLoading(true);
      setStorageIndex(await fetchStorageIndex());
      setStorageError('');
    } catch (error) {
      setStorageError(error instanceof Error ? error.message : 'unknown error');
      setStorageIndex(null);
    } finally {
      setStorageLoading(false);
    }
  }

  async function loadPreferencesState() {
    try {
      const preferencesPayload = await fetchWebPreferences();
      const nextLanguage = isAppLanguage(preferencesPayload.language)
        ? preferencesPayload.language
        : DEFAULT_APP_LANGUAGE;
      const nextThemeId = isWebThemeId(preferencesPayload.themeId)
        ? preferencesPayload.themeId
        : DEFAULT_WEB_THEME_ID;
      currentLanguageRef.current = nextLanguage;
      currentThemeIdRef.current = nextThemeId;
      setCurrentLanguage(nextLanguage);
      setCurrentThemeId(nextThemeId);
    } catch (error) {
      console.warn('Failed to load web preferences, using defaults.', error);
    }
  }

  async function loadRecordingEntry(fileName: string) {
    try {
      setRecordingEntryLoading(true);
      setRecordingEntryError('');
      setRecordingSelectedEntryKey(buildEntryKey('recording', fileName));
      setRecordingSelectedEntry(await fetchStorageEntry('recording', fileName));
    } catch (error) {
      setRecordingEntryError(error instanceof Error ? error.message : 'unknown error');
      setRecordingSelectedEntry(null);
    } finally {
      setRecordingEntryLoading(false);
    }
  }

  function enqueuePreferencesSave(nextLanguage: AppLanguage, nextThemeId: WebThemeId) {
    preferencesSaveQueueRef.current = preferencesSaveQueueRef.current
      .catch(() => undefined)
      .then(() => saveWebPreferences(nextLanguage, nextThemeId))
      .catch((error) => {
        console.warn('Failed to persist web preferences.', error);
      });
  }

  function handleLanguageChange(nextLanguage: AppLanguage) {
    currentLanguageRef.current = nextLanguage;
    setCurrentLanguage(nextLanguage);
    enqueuePreferencesSave(nextLanguage, currentThemeIdRef.current);
  }

  function handleThemeChange(nextThemeId: WebThemeId) {
    currentThemeIdRef.current = nextThemeId;
    setCurrentThemeId(nextThemeId);
    enqueuePreferencesSave(currentLanguageRef.current, nextThemeId);
  }

  async function loadGraphEntry(fileName: string) {
    try {
      setGraphEntryLoading(true);
      setGraphEntryError('');
      setGraphSelectedEntryKey(buildEntryKey('graph', fileName));
      setGraphSelectedEntry(await fetchStorageEntry('graph', fileName));
    } catch (error) {
      setGraphEntryError(error instanceof Error ? error.message : 'unknown error');
      setGraphSelectedEntry(null);
    } finally {
      setGraphEntryLoading(false);
    }
  }

  function handleGraphEntryReloaded(entry: StorageEntryPayload) {
    setStorageIndex((currentValue) => upsertStorageEntryIntoIndex(currentValue, entry));
    setGraphEntryError('');
    setGraphSelectedEntry(entry);
    setGraphSelectedEntryKey(buildEntryKey(entry.kind, entry.fileName));
    syncAppLocation(
      {
        page: 'graph',
        kind: entry.kind,
        fileName: entry.fileName,
      },
      'replace',
    );
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
      const confirmed = window.confirm(
        pickLocalizedText(
          currentLanguage,
          '当前 graph 页面有未保存修改，切换文件会丢失本地草稿，是否继续？',
          'The current graph page has unsaved local changes. Switching files will discard the local draft. Continue?',
        ),
      );
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

  if (appLocation.page === 'recording') {
    return (
      <RecordingPage
        bridgeError={bridgeError}
        bridgeStateClassName={bridgeStateClassName}
        bridgeStateLabel={bridgeStateLabel}
        currentLanguage={currentLanguage}
        onThemeChange={handleThemeChange}
        onLanguageChange={handleLanguageChange}
        onRecordingFileChange={handleRecordingFileChange}
        onRefreshStorageIndex={() => void loadStorageIndexState()}
        recordingBundle={recordingBundle}
        recordingEntries={recordingEntries}
        recordingEntryError={recordingEntryError}
        recordingEntryLoading={recordingEntryLoading}
        recordingSelectedEntry={recordingSelectedEntry}
        selectedFileName={appLocation.page === 'recording' ? appLocation.fileName : ''}
        storageError={storageError}
        storageLoading={storageLoading}
        themeId={currentThemeId}
      />
    );
  }

  return (
    <GraphPage
      bridgeError={bridgeError}
      bridgeStateClassName={bridgeStateClassName}
      bridgeStateLabel={bridgeStateLabel}
      currentLanguage={currentLanguage}
      graphBundle={graphBundle}
      graphEntries={graphEntries}
      graphEntryError={graphEntryError}
      graphEntryLoading={graphEntryLoading}
      graphPageDirty={graphPageDirty}
      graphSelectedEntry={graphSelectedEntry}
      onDirtyStateChange={setGraphPageDirty}
      onGraphEntryReloaded={handleGraphEntryReloaded}
      onGraphFileChange={handleGraphFileChange}
      onLanguageChange={handleLanguageChange}
      onRefreshStorageIndex={() => void loadStorageIndexState()}
      onThemeChange={handleThemeChange}
      selectedFileName={appLocation.fileName}
      storageError={storageError}
      storageLoading={storageLoading}
      themeId={currentThemeId}
    />
  );
}
