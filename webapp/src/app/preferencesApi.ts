import type { WebPreferencesPayload } from './types';

/**
 * 读取客户端持久化的网页偏好。
 */
export async function fetchWebPreferences(): Promise<WebPreferencesPayload> {
  const response = await fetch('./api/preferences', {
    cache: 'no-store',
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as WebPreferencesPayload;
}

/**
 * 保存网页语言与主题偏好。
 */
export async function saveWebPreferences(
  language: string,
  themeId: string,
): Promise<WebPreferencesPayload> {
  const response = await fetch('./api/preferences', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
    },
    body: JSON.stringify({
      language,
      themeId,
    }),
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as WebPreferencesPayload;
}
