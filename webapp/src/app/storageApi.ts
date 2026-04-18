import type {
  BridgePingPayload,
  StorageEntryPayload,
  StorageEntrySummary,
  StorageIndexPayload,
} from './types';

export function findStorageEntry(
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

export async function fetchBridgeStatus(): Promise<BridgePingPayload> {
  const response = await fetch('./api/ping', {
    cache: 'no-store',
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as BridgePingPayload;
}

export async function fetchStorageIndex(): Promise<StorageIndexPayload> {
  const response = await fetch('./api/storage/index', {
    cache: 'no-store',
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as StorageIndexPayload;
}

export async function fetchStorageEntry(
  kind: string,
  fileName: string,
): Promise<StorageEntryPayload> {
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
