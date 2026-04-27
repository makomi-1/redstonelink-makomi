package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * 去除文字阴影的多行输入框工厂。
 * <p>
 * 1.21.11 的 `MultiLineEditBox` 改为 builder 构造且不再开放原来的子类扩展入口，
 * 这里收敛为统一工厂，优先保留文本、滚动、选区与焦点行为。
 * </p>
 */
final class ShadowlessCounterMultiLineEditBox {
	private ShadowlessCounterMultiLineEditBox() {
	}

	/**
	 * 创建默认风格的多行输入框。
	 */
	static MultiLineEditBox create(
		Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder
	) {
		return MultiLineEditBox
			.builder()
			.setX(x)
			.setY(y)
			.setPlaceholder(placeholder)
			.setTextShadow(false)
			.setShowBackground(true)
			.setShowDecorations(true)
			.build(font, width, height, message);
	}
}
