import {
  formatThemeLabel,
  WEB_THEME_OPTIONS,
  type WebThemeId,
} from '../app/theme';
import { pickLocalizedText, type AppLanguage } from '../app/i18n';

type ThemeSwitcherProps = {
  currentThemeId: WebThemeId;
  language: AppLanguage;
  onThemeChange: (themeId: WebThemeId) => void;
};

/**
 * 主题切换器。
 * <p>
 * 这里只负责展示主题选项并切换当前主题，
 * 不承载任何业务状态，便于在不同页面复用。
 * </p>
 */
export default function ThemeSwitcher({
  currentThemeId,
  language,
  onThemeChange,
}: ThemeSwitcherProps) {
  return (
    <div className="theme-switcher">
      <span className="theme-switcher-label">
        {pickLocalizedText(language, '主题', 'Theme')}
      </span>
      <div className="theme-switcher-grid">
        {WEB_THEME_OPTIONS.map((themeOption) => {
          const active = currentThemeId === themeOption.id;
          return (
            <button
              key={themeOption.id}
              type="button"
              className={`theme-option theme-option--${themeOption.id}${active ? ' is-active' : ''}`}
              aria-pressed={active}
              onClick={() => onThemeChange(themeOption.id)}
            >
              <span className="theme-option-title">
                {formatThemeLabel(themeOption.id, language)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
