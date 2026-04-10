package com.makomi.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * 可定制底色的多行输入框。
 * <p>
 * 在保留原版 `MultiLineEditBox` 文本、滚动和光标行为的前提下，
 * 仅接管输入框背景层绘制，便于不同 GUI 以覆写方式复用统一皮肤能力。
 * </p>
 */
final class StyledMultiLineEditBox extends MultiLineEditBox {
	private final Style style;

	/**
	 * @param style 输入框皮肤配置
	 */
	StyledMultiLineEditBox(
		net.minecraft.client.gui.Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder,
		Style style
	) {
		super(font, x, y, width, height, message, placeholder);
		this.style = style == null ? Style.defaultStyle() : style;
	}

	@Override
	protected void renderBackground(GuiGraphics guiGraphics) {
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		int borderColor = isFocused() ? style.focusedBorderColor() : style.borderColor();
		guiGraphics.fill(left, top, right, bottom, borderColor);
		guiGraphics.fill(left + 1, top + 1, right - 1, bottom - 1, style.backgroundColor());
	}

	/**
	 * 输入框皮肤配置。
	 */
	record Style(int backgroundColor, int borderColor, int focusedBorderColor) {
		/**
		 * 默认皮肤尽量贴近原版深色输入框观感。
		 */
		static Style defaultStyle() {
			return new Style(0xFF202020, 0xFF5A5A5A, 0xFFFFFFFF);
		}
	}
}
