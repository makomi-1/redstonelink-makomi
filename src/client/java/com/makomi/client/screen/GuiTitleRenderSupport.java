package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * GUI 标题描边字渲染支持。
 * <p>
 * 统一封装主标题与副标题的轻描边绘制，避免各个界面重复维护
 * 居中坐标与描边配色细节，并优先保证标题稳定可见。
 * </p>
 */
final class GuiTitleRenderSupport {
	private static final TitleStyle PRIMARY_TITLE_STYLE = new TitleStyle(0xFFFFFFFF, 0xFF101010);
	private static final int[][] OUTLINE_OFFSETS = {
		{ 0, -1 },
		{ -1, 0 },
		{ 1, 0 },
		{ 0, 1 },
	};

	private GuiTitleRenderSupport() {
	}

	/**
	 * 绘制默认主标题样式。
	 */
	static void drawCenteredPrimaryTitle(GuiGraphicsExtractor guiGraphics, Font font, Component text, int centerX, int y) {
		drawCenteredOutlinedText(guiGraphics, font, text, centerX, y, PRIMARY_TITLE_STYLE);
	}

	/**
	 * 按指定文字颜色绘制副标题，并自动生成更深的描边色。
	 */
	static void drawCenteredSecondaryTitle(GuiGraphicsExtractor guiGraphics, Font font, Component text, int centerX, int y, int textColor) {
		drawCenteredOutlinedText(guiGraphics, font, text, centerX, y, TitleStyle.secondary(textColor));
	}

	/**
	 * 绘制居中描边文本。
	 */
	static void drawCenteredOutlinedText(
		GuiGraphicsExtractor guiGraphics,
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
		guiGraphics.text(font, visualText, left, y, style.textColor(), false);
	}

	/**
	 * 使用 GUI 提交流程下的四向轻描边，优先保证标题稳定可见。
	 */
	private static void drawOutline(GuiGraphicsExtractor guiGraphics, Font font, FormattedCharSequence text, int left, int top, int outlineColor) {
		for (int[] offset : OUTLINE_OFFSETS) {
			if (offset == null || offset.length < 2) {
				continue;
			}
			guiGraphics.text(font, text, left + offset[0], top + offset[1], outlineColor, false);
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
