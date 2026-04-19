import {
  WEB_THEME_OPTIONS,
  type WebThemeId,
} from '../app/theme';

type ThemeSwitcherProps = {
  currentThemeId: WebThemeId;
  onThemeChange: (themeId: WebThemeId) => void;
};

/**
 * 主题预览切换器。
 * <p>
 * 这里只负责展示模板和切换当前主题，
 * 不承载任何业务状态，便于在不同页面复用。
 * </p>
 */
export default function ThemeSwitcher({
  currentThemeId,
  onThemeChange,
}: ThemeSwitcherProps) {
  return (
    <div className="theme-switcher">
      <div className="theme-switcher-header">
        <div>
          <span className="section-tag">Theme Preview</span>
          <h2>网页视觉主题预览</h2>
        </div>
        <p className="theme-switcher-caption">
          当前仅切换视觉层，先对比模板风格，再决定最终定稿方向。
        </p>
      </div>
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
              <span className="theme-option-caption">
                {themeOption.caption}
              </span>
              <span className="theme-option-palette">
                {themeOption.paletteLabel}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
