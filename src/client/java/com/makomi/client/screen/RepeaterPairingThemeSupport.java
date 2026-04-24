package com.makomi.client.screen;

/**
 * 转发器入口下 pairing GUI 的统一主题支持。
 * <p>
 * 当 `triggerSource/core` 配对界面由转发器打开时，背景和控件皮肤都应切到转发器主题，
 * 避免同一操作链路出现“标题是转发器、控件却还是普通节点配色”的割裂感。
 * </p>
 */
final class RepeaterPairingThemeSupport {
	private static final int THEME_BORDER_COLOR = GuiBackgroundRenderSupport.BackgroundPreset.REPEATER.borderColor();
	private static final int THEME_FILL_COLOR = 0xCC47BF53;
	private static final int THEME_HOVER_FILL_COLOR = 0xE05AD868;
	private static final int THEME_DISABLED_FILL_COLOR = 0x9949844E;
	private static final int THEME_FOCUSED_BORDER_COLOR = 0xFF96FF9F;
	private static final int THEME_DISABLED_BORDER_COLOR = 0xFF2F7C37;
	private static final int THEME_TEXT_COLOR = 0xFFF4FFF4;
	private static final int THEME_DISABLED_TEXT_COLOR = 0xFFCBE6CE;

	private static final StyledMultiLineEditBox.Style MULTI_LINE_INPUT_STYLE = new StyledMultiLineEditBox.Style(
		0xFF12651A,
		0xFF0B4210,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);
	private static final StyledEditBox.Style EDIT_BOX_STYLE = new StyledEditBox.Style(
		THEME_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);
	private static final StyledButton.Style BUTTON_STYLE = new StyledButton.Style(
		0xE047BF53,
		THEME_HOVER_FILL_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);

	private RepeaterPairingThemeSupport() {
	}

	/**
	 * @return 转发器 pairing 背景预设
	 */
	static GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.REPEATER;
	}

	/**
	 * @return 转发器多行输入框皮肤
	 */
	static StyledMultiLineEditBox.Style inputBoxStyle() {
		return MULTI_LINE_INPUT_STYLE;
	}

	/**
	 * @return 转发器别名单行输入框皮肤
	 */
	static StyledEditBox.Style aliasInputStyle() {
		return EDIT_BOX_STYLE;
	}

	/**
	 * @return 转发器按钮皮肤
	 */
	static StyledButton.Style actionButtonStyle() {
		return BUTTON_STYLE;
	}
}
