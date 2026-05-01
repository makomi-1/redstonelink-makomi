package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 去除文字阴影的多行输入框工厂与装饰支持。
 * <p>
 * 26.1 下 `MultiLineEditBox` 通过 builder 构造且内部计数绘制不再开放，
 * 因此保留原版编辑控件本体，再由屏幕层补画无阴影容量计数。
 * </p>
 */
final class ShadowlessCounterMultiLineEditBox {
	private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
	private static final int DEFAULT_CURSOR_COLOR = 0xFFFFFFFF;
	private static final int DEFAULT_COUNTER_TEXT_COLOR = 0xFFA0A0A0;

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
			.setTextColor(DEFAULT_TEXT_COLOR)
			.setTextShadow(false)
			.setCursorColor(DEFAULT_CURSOR_COLOR)
			.setShowBackground(true)
			// 关闭原版 decorations，避免重复绘制带阴影的容量计数。
			.setShowDecorations(false)
			.build(font, width, height, message);
	}

	/**
	 * 在输入框下方补画无阴影容量计数。
	 *
	 * @param box 目标输入框
	 * @param font 字体
	 * @param characterLimit 当前字符上限；小于等于 0 表示不显示
	 */
	static void renderCharacterLimitCounter(
		net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics,
		MultiLineEditBox box,
		Font font,
		int characterLimit
	) {
		renderCharacterLimitCounter(guiGraphics, box, font, characterLimit, DEFAULT_COUNTER_TEXT_COLOR);
	}

	/**
	 * 在输入框下方补画无阴影容量计数。
	 *
	 * @param counterTextColor 计数字体颜色
	 */
	static void renderCharacterLimitCounter(
		net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics,
		MultiLineEditBox box,
		Font font,
		int characterLimit,
		int counterTextColor
	) {
		if (guiGraphics == null || box == null || font == null || characterLimit <= 0 || !box.visible) {
			return;
		}
		Component counterText = Component.translatable(
			"gui.multiLineEditBox.character_limit",
			box.getValue().length(),
			characterLimit
		);
		int counterX = box.getX() + box.getWidth() - font.width(counterText);
		int counterY = box.getY() + box.getHeight() + 4;
		guiGraphics.text(font, counterText, counterX, counterY, withAlpha(box, counterTextColor), false);
	}

	/**
	 * 多行输入框与单行输入框保持同样的 alpha 混合策略，避免界面淡出阶段颜色突变。
	 */
	private static int withAlpha(MultiLineEditBox box, int argbColor) {
		int baseAlpha = (argbColor >>> 24) & 0xFF;
		float widgetAlpha = box == null ? 1.0F : box.getAlpha();
		int mixedAlpha = Mth.clamp(Math.round(baseAlpha * widgetAlpha), 0, 255);
		return (mixedAlpha << 24) | (argbColor & 0x00FFFFFF);
	}
}
