export type WebThemeId =
  | 'future-command'
  | 'lab-minimal'
  | 'industrial-tech';

export type WebThemeOption = {
  id: WebThemeId;
  label: string;
  caption: string;
  paletteLabel: string;
};

export const DEFAULT_WEB_THEME_ID: WebThemeId = 'future-command';

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
    label: '未来指挥台',
    caption: '冷白信息面板 + 深蓝指挥台底色，配合红色操作高亮。',
    paletteLabel: '冷白 / 深蓝 / 红',
  },
  {
    id: 'lab-minimal',
    label: '实验室极简',
    caption: '白底与冷蓝信息层级，干净、克制、现代。',
    paletteLabel: '白 / 浅灰 / 蓝',
  },
  {
    id: 'industrial-tech',
    label: '工业科技',
    caption: '浅灰工业底色 + 蓝橙强调，兼顾科技感与温度。',
    paletteLabel: '白 / 蓝 / 橙',
  },
] as const;
