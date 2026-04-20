import {
  formatLanguageLabel,
  pickLocalizedText,
  type AppLanguage,
} from '../app/i18n';

type LanguageSwitcherProps = {
  currentLanguage: AppLanguage;
  onLanguageChange: (language: AppLanguage) => void;
};

const LANGUAGE_OPTIONS: readonly AppLanguage[] = ['zh-CN', 'en-US'];

/**
 * 语言切换器。
 * <p>
 * 这里只负责展示语言选项并回传当前选择，
 * 具体状态与持久化仍由顶层页面统一管理。
 * </p>
 */
export default function LanguageSwitcher({
  currentLanguage,
  onLanguageChange,
}: LanguageSwitcherProps) {
  return (
    <div className="theme-switcher">
      <span className="theme-switcher-label">
        {pickLocalizedText(currentLanguage, '语言', 'Language')}
      </span>
      <div className="theme-switcher-grid">
        {LANGUAGE_OPTIONS.map((languageOption) => {
          const active = currentLanguage === languageOption;
          return (
            <button
              key={languageOption}
              type="button"
              className={`theme-option${active ? ' is-active' : ''}`}
              aria-pressed={active}
              onClick={() => onLanguageChange(languageOption)}
            >
              <span className="theme-option-title">
                {formatLanguageLabel(currentLanguage, languageOption)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
