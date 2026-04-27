package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * GUI 标题描边字渲染支持。
 * <p>
 * 统一封装主标题与副标题的原版八向描边绘制，避免各个界面重复维护
 * 居中坐标与描边配色细节。
 * </p>
 */
final class GuiTitleRenderSupport {
	private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
	private static final TitleStyle PRIMARY_TITLE_STYLE = new TitleStyle(0xFFFFFFFF, 0xFF101010);

	private GuiTitleRenderSupport() {
	}

	/**
	 * 绘制默认主标题样式。
	 */
	static void drawCenteredPrimaryTitle(GuiGraphics guiGraphics, Font font, Component text, int centerX, int y) {
		drawCenteredOutlinedText(guiGraphics, font, text, centerX, y, PRIMARY_TITLE_STYLE);
	}

	/**
	 * 按指定文字颜色绘制副标题，并自动生成更深的描边色。
	 */
	static void drawCenteredSecondaryTitle(GuiGraphics guiGraphics, Font font, Component text, int centerX, int y, int textColor) {
		drawCenteredOutlinedText(guiGraphics, font, text, centerX, y, TitleStyle.secondary(textColor));
	}

	/**
	 * 绘制居中描边文本。
	 */
	static void drawCenteredOutlinedText(
		GuiGraphics guiGraphics,
		Font font,
		Component text,
		int centerX,
		int y,
		TitleStyle style
	) {
		if (guiGraphics == null || font == null || text == null || style == null) {
			return;
		}
		FormattedCharSequence visualText = text.getVisualOrderText();
		int left = centerX - (font.width(visualText) / 2);
		drawOutline(guiGraphics, font, visualText, left, y, style.outlineColor());
		guiGraphics.drawString(font, visualText, left, y, style.textColor(), false);
	}

	/**
	 * 使用八向位移模拟描边，兼容 1.21.11 中移除的旧批量描边绘制入口。
	 */
	private static void drawOutline(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int left, int top, int outlineColor) {
		for (int offsetX = -1; offsetX <= 1; offsetX++) {
			for (int offsetY = -1; offsetY <= 1; offsetY++) {
				if (offsetX == 0 && offsetY == 0) {
					continue;
				}
				guiGraphics.drawString(font, text, left + offsetX, top + offsetY, outlineColor, false);
			}
		}
	}

	/**
	 * 标题描边样式。
	 */
	record TitleStyle(int textColor, int outlineColor) {
		/**
		 * 根据当前文字颜色推导更深的描边色，保证不同主题都能保留色相。
		 */
		static TitleStyle secondary(int textColor) {
			return new TitleStyle(textColor, deriveOutlineColor(textColor));
		}

		private static int deriveOutlineColor(int textColor) {
			int alpha = (textColor >>> 24) & 0xFF;
			int red = (textColor >>> 16) & 0xFF;
			int green = (textColor >>> 8) & 0xFF;
			int blue = textColor & 0xFF;
			int mixedAlpha = Math.max(alpha, 0xE0);
			int outlineRed = Math.max(0, Math.round(red * 0.22F));
			int outlineGreen = Math.max(0, Math.round(green * 0.22F));
			int outlineBlue = Math.max(0, Math.round(blue * 0.22F));
			return (mixedAlpha << 24) | (outlineRed << 16) | (outlineGreen << 8) | outlineBlue;
		}
	}
}
