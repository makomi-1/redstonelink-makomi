export type AppLanguage = 'zh-CN' | 'en-US';

export const DEFAULT_APP_LANGUAGE: AppLanguage = 'zh-CN';
export const APP_LANGUAGE_STORAGE_KEY = 'rl.web.language';

const SUPPORTED_APP_LANGUAGES: readonly AppLanguage[] = ['zh-CN', 'en-US'];

/**
 * 判断给定值是否属于当前前端支持的语言。
 */
export function isAppLanguage(value: string): value is AppLanguage {
  return SUPPORTED_APP_LANGUAGES.includes(value as AppLanguage);
}

/**
 * 读取本地持久化语言；若缺失或非法则回退默认语言。
 */
export function readPersistedAppLanguage(): AppLanguage {
  if (typeof window === 'undefined') {
    return DEFAULT_APP_LANGUAGE;
  }
  const persistedLanguage = window.localStorage.getItem(APP_LANGUAGE_STORAGE_KEY);
  return persistedLanguage != null && isAppLanguage(persistedLanguage)
    ? persistedLanguage
    : DEFAULT_APP_LANGUAGE;
}

/**
 * 语言切换统一走二选一文案分发，避免在各组件重复写条件判断。
 */
export function pickLocalizedText<T>(
  language: AppLanguage,
  chineseText: T,
  englishText: T,
): T {
  return language === 'zh-CN' ? chineseText : englishText;
}

/**
 * 语言切换器按钮标签。
 */
export function formatLanguageLabel(language: AppLanguage, option: AppLanguage): string {
  if (option === 'zh-CN') {
    return language === 'zh-CN' ? '中文' : 'Chinese';
  }
  return 'English';
}
