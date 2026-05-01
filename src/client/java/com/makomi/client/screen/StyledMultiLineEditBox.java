package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * 可定制主题参数的多行输入框工厂。
 * <p>
 * 26.1 下多行输入框不再开放背景/计数子类扩展，因此这里把输入框本体与
 * 主题壳绘制职责拆开：控件仍使用原版 builder，主题边框与无阴影计数交给屏幕层补画。
 * </p>
 */
final class StyledMultiLineEditBox {
	private StyledMultiLineEditBox() {
	}

	/**
	 * @param style 输入框皮肤配置
	 */
	static MultiLineEditBox create(
		Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder,
		Style style
	) {
		Style resolvedStyle = style == null ? Style.defaultStyle() : style;
		return MultiLineEditBox
			.builder()
			.setX(x)
			.setY(y)
			.setPlaceholder(placeholder)
			.setTextColor(0xFFFFFFFF)
			.setCursorColor(resolvedStyle.focusedBorderColor())
			.setTextShadow(false)
			// 主题边框与容量计数改由屏幕层统一补画，避免原版皮肤覆盖自定义配色。
			.setShowBackground(false)
			.setShowDecorations(false)
			.build(font, width, height, message);
	}

	/**
	 * 为多行输入框补画主题边框与底色。
	 */
	static void renderBackground(
		GuiGraphicsExtractor guiGraphics,
		MultiLineEditBox box,
		Style style
	) {
		if (guiGraphics == null || box == null || !box.visible) {
			return;
		}
		Style resolvedStyle = style == null ? Style.defaultStyle() : style;
		int left = box.getX();
		int top = box.getY();
		int right = left + box.getWidth();
		int bottom = top + box.getHeight();
		int resolvedBorderColor = box.isFocused() ? resolvedStyle.focusedBorderColor() : resolvedStyle.borderColor();
		guiGraphics.fill(left, top, right, bottom, resolvedBorderColor);
		guiGraphics.fill(left + 1, top + 1, right - 1, bottom - 1, resolvedStyle.backgroundColor());
	}

	/**
	 * 输入框皮肤配置。
	 */
	record Style(int backgroundColor, int borderColor, int focusedBorderColor, int counterTextColor) {
		/**
		 * 默认皮肤尽量贴近原版深色输入框观感。
		 */
		static Style defaultStyle() {
			return new Style(0xFF202020, 0xFF5A5A5A, 0xFFFFFFFF, 0xFFA0A0A0);
		}
	}
}
