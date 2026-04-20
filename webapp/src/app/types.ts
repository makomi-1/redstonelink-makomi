export type BridgePingPayload = {
  status: string;
  modId: string;
  bridgeVersion: string;
  mode: string;
  startedAtEpochMillis: number;
  baseUrl: string;
};

export type StorageEntrySummary = {
  kind: string;
  fileName: string;
  relativePath: string;
  compressed: boolean;
  sizeBytes: number;
  lastModifiedEpochMillis: number;
};

export type StorageCategorySummary = {
  kind: string;
  label: string;
  directoryName: string;
  fileExtension: string;
  compressed: boolean;
  entryCount: number;
  entries: StorageEntrySummary[];
};

export type StorageIndexPayload = {
  status: string;
  rootPath: string;
  refreshedAtEpochMillis: number;
  categories: StorageCategorySummary[];
};

export type StorageEntryPayload = {
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

export type WebPreferencesPayload = {
  status: string;
  language: string;
  themeId: string;
};

export type AppPage = 'recording' | 'graph';

export type AppLocation = {
  page: AppPage;
  kind: string;
  fileName: string;
};
