import { describe, expect, it, vi } from 'vitest';
import {
  fetchBridgeStatus,
  fetchStorageEntry,
  fetchStorageIndex,
  findStorageEntry,
} from './storageApi';
import {
  createTestBridgePayload,
  createTestStorageCategory,
  createTestStorageEntryPayload,
  createTestStorageEntrySummary,
  createTestStorageIndex,
} from '../test/factories';
import { createJsonResponse } from '../test/http';

describe('storageApi', () => {
  it('findStorageEntry 会在索引里定位指定 kind 与文件名', () => {
    const targetEntry = createTestStorageEntrySummary({
      kind: 'graph',
      fileName: 'snapshot.json',
    });
    const storageIndex = createTestStorageIndex([
      createTestStorageCategory({
        kind: 'graph',
        entries: [targetEntry],
      }),
    ]);

    expect(findStorageEntry(storageIndex, 'graph', 'snapshot.json')).toEqual(targetEntry);
    expect(findStorageEntry(storageIndex, 'graph', 'missing.json')).toBeNull();
    expect(findStorageEntry(null, 'graph', 'snapshot.json')).toBeNull();
  });

  it('fetchBridgeStatus 成功时返回 ping payload，并使用 no-store', async () => {
    const bridgePayload = createTestBridgePayload();
    const fetchMock = vi.fn().mockResolvedValue(createJsonResponse(bridgePayload));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchBridgeStatus()).resolves.toEqual(bridgePayload);
    expect(fetchMock).toHaveBeenCalledWith('./api/ping', {
      cache: 'no-store',
    });
  });

  it('fetchStorageIndex 在 HTTP 失败时抛出状态码错误', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(createJsonResponse({}, { ok: false, status: 503 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchStorageIndex()).rejects.toThrow('HTTP 503');
  });

  it('fetchStorageEntry 会编码查询参数并返回 entry payload', async () => {
    const entryPayload = createTestStorageEntryPayload({
      kind: 'graph',
      fileName: 'demo graph#.json',
      textContent: '{"kind":"graphSnapshotBundle"}',
    });
    const fetchMock = vi.fn().mockResolvedValue(createJsonResponse(entryPayload));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchStorageEntry('graph', 'demo graph#.json')).resolves.toEqual(entryPayload);
    expect(fetchMock).toHaveBeenCalledWith(
      './api/storage/entry?kind=graph&name=demo%20graph%23.json',
      {
        cache: 'no-store',
      },
    );
  });
});
