import {
  WEB_THEME_OPTIONS,
  type WebThemeId,
} from '../app/theme';

type ThemeSwitcherProps = {
  currentThemeId: WebThemeId;
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
  onThemeChange,
}: ThemeSwitcherProps) {
  return (
    <div className="theme-switcher">
      <span className="theme-switcher-label">主题</span>
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
              <span className="theme-option-title">{themeOption.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
