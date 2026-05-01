package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.RedstoneLinkStyledMultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * 可定制主题参数的多行输入框工厂。
 * <p>
 * 1.21.11 不再允许沿用旧子类覆盖背景绘制，这里先保留统一构造入口与主题参数，
 * 后续若需要恢复自定义皮肤，只需在这一层替换实现。
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
		return new RedstoneLinkStyledMultiLineEditBox(
			font,
			x,
			y,
			width,
			height,
			message,
			placeholder,
			resolvedStyle.backgroundColor(),
			resolvedStyle.borderColor(),
			resolvedStyle.focusedBorderColor(),
			resolvedStyle.counterTextColor()
		);
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
