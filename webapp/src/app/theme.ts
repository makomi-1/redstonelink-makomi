import type { AppLanguage } from './i18n';

export type WebThemeId =
  | 'future-command'
  | 'lab-minimal'
  | 'industrial-tech';

export type WebThemeOption = {
  id: WebThemeId;
};

export const DEFAULT_WEB_THEME_ID: WebThemeId = 'future-command';
export const WEB_THEME_STORAGE_KEY = 'rl.web.theme';

/**
 * 主题预览列表。
 * <p>
 * 当前阶段只提供少量高辨识度模板，
 * 方便先在真实页面中挑方向，再继续做最终定稿。
 * </p>
 */
export const WEB_THEME_OPTIONS: readonly WebThemeOption[] = [
  {
    id: 'future-command',
  },
  {
    id: 'lab-minimal',
  },
  {
    id: 'industrial-tech',
  },
] as const;

/**
 * 判断给定值是否属于受支持主题。
 */
export function isWebThemeId(value: string): value is WebThemeId {
  return WEB_THEME_OPTIONS.some((themeOption) => themeOption.id === value);
}

/**
 * 读取本地持久化主题；若缺失或非法则回退默认主题。
 */
export function readPersistedWebThemeId(): WebThemeId {
  if (typeof window === 'undefined') {
    return DEFAULT_WEB_THEME_ID;
  }
  const persistedThemeId = window.localStorage.getItem(WEB_THEME_STORAGE_KEY);
  return persistedThemeId != null && isWebThemeId(persistedThemeId)
    ? persistedThemeId
    : DEFAULT_WEB_THEME_ID;
}

/**
 * 按语言返回主题名称，避免主题切换器绑定固定中文。
 */
export function formatThemeLabel(themeId: WebThemeId, language: AppLanguage): string {
  switch (themeId) {
    case 'future-command':
      return language === 'zh-CN' ? '未来指挥台' : 'Future Command Deck';
    case 'lab-minimal':
      return language === 'zh-CN' ? '实验室极简' : 'Lab Minimal';
    case 'industrial-tech':
      return language === 'zh-CN' ? '工业科技' : 'Industrial Tech';
    default:
      return themeId;
  }
}
