import { describe, expect, it, vi } from 'vitest';
import { fetchWebPreferences, saveWebPreferences } from './preferencesApi';
import { createTestWebPreferencesPayload } from '../test/factories';
import { createJsonResponse } from '../test/http';

describe('preferencesApi', () => {
  it('fetchWebPreferences 成功时返回偏好 payload，并使用 no-store', async () => {
    const preferencesPayload = createTestWebPreferencesPayload({
      language: 'en-US',
      themeId: 'lab-minimal',
    });
    const fetchMock = vi.fn().mockResolvedValue(createJsonResponse(preferencesPayload));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchWebPreferences()).resolves.toEqual(preferencesPayload);
    expect(fetchMock).toHaveBeenCalledWith('./api/preferences', {
      cache: 'no-store',
    });
  });

  it('saveWebPreferences 会发送 JSON 请求体并返回保存结果', async () => {
    const preferencesPayload = createTestWebPreferencesPayload({
      language: 'en-US',
      themeId: 'lab-minimal',
    });
    const fetchMock = vi.fn().mockResolvedValue(createJsonResponse(preferencesPayload));
    vi.stubGlobal('fetch', fetchMock);

    await expect(saveWebPreferences('en-US', 'lab-minimal')).resolves.toEqual(
      preferencesPayload,
    );
    expect(fetchMock).toHaveBeenCalledWith('./api/preferences', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify({
        language: 'en-US',
        themeId: 'lab-minimal',
      }),
    });
  });

  it('saveWebPreferences 在 HTTP 失败时抛出状态码错误', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(createJsonResponse({}, { ok: false, status: 429 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(saveWebPreferences('zh-CN', 'future-command')).rejects.toThrow(
      'HTTP 429',
    );
  });
});
