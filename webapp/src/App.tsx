import { useEffect, useMemo, useState } from 'react';
import GraphPage from './app/GraphPage';
import RecordingPage from './app/RecordingPage';
import { buildAppHref, buildEntryKey, parseAppLocation } from './app/location';
import {
  fetchBridgeStatus,
  fetchStorageEntry,
  fetchStorageIndex,
  findStorageEntry,
} from './app/storageApi';
import { DEFAULT_WEB_THEME_ID, type WebThemeId } from './app/theme';
import type {
  AppLocation,
  StorageEntryPayload,
  StorageIndexPayload,
} from './app/types';
import { parseGraphSnapshotBundle } from './graphTypes';
import { parseRecordingBundle } from './recordingTypes';

/**
 * 网页主入口。
 * <p>
 * 当前入口仅承载 graph 与 recording 两个独立主页面。
 * </p>
 */
export default function App() {
  const [appLocation, setAppLocation] = useState<AppLocation>(() => parseAppLocation());
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

  const bridgeStateLabel = bridgeLoading ? '连接中' : bridgeError ? '未连接' : '已连接';
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
  }, []);

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

  if (appLocation.page === 'recording') {
    return (
      <RecordingPage
        bridgeError={bridgeError}
        bridgeStateClassName={bridgeStateClassName}
        bridgeStateLabel={bridgeStateLabel}
        onThemeChange={setCurrentThemeId}
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
      graphBundle={graphBundle}
      graphEntries={graphEntries}
      graphEntryError={graphEntryError}
      graphEntryLoading={graphEntryLoading}
      graphPageDirty={graphPageDirty}
      graphSelectedEntry={graphSelectedEntry}
      onDirtyStateChange={setGraphPageDirty}
      onGraphFileChange={handleGraphFileChange}
      onRefreshStorageIndex={() => void loadStorageIndexState()}
      onThemeChange={setCurrentThemeId}
      selectedFileName={appLocation.fileName}
      storageError={storageError}
      storageLoading={storageLoading}
      themeId={currentThemeId}
    />
  );
}
