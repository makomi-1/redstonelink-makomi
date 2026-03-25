package com.makomi.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 近外显实际绘制支持。
 * <p>
 * 负责底板与文字的实际 HUD 绘制，不承担快照读取和文案拼接职责。
 * </p>
 */
final class LinkSerialHudOverlayDrawSupport {
	private static final int BACKGROUND_COLOR = 0xD012141A;
	private static final int BORDER_COLOR = 0xA0363E4D;

	private LinkSerialHudOverlayDrawSupport() {
	}

	/**
	 * 在屏幕中心绘制深色底板文本。
	 *
	 * @param guiGraphics HUD 绘图上下文
	 * @param font 当前字体
	 * @param lines 待绘制文本
	 * @param textColor 文字颜色
	 * @param scale 字体缩放
	 */
	static void drawCenteredWithDeepBackground(
		GuiGraphics guiGraphics,
		Font font,
		List<String> lines,
		int textColor,
		float scale
	) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		int screenWidth = guiGraphics.guiWidth();
		int screenHeight = guiGraphics.guiHeight();
		float maxLineWidth = 0.0F;
		for (String line : lines) {
			maxLineWidth = Math.max(maxLineWidth, font.width(line));
		}
		float totalLineHeight = (font.lineHeight * lines.size())
			+ (LinkSerialHudOverlayLayoutSupport.LINE_SPACING * Math.max(0, lines.size() - 1));
		float scaledTextWidth = maxLineWidth * scale;
		float scaledTextHeight = totalLineHeight * scale;
		LinkSerialHudOverlayLayoutSupport.OverlayLayout layout = LinkSerialHudOverlayLayoutSupport.resolveLayout(
			screenWidth,
			screenHeight,
			scaledTextWidth,
			scaledTextHeight
		);

		guiGraphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), BACKGROUND_COLOR);
		guiGraphics.fill(layout.left() - 1, layout.top() - 1, layout.right() + 1, layout.top(), BORDER_COLOR);
		guiGraphics.fill(layout.left() - 1, layout.bottom(), layout.right() + 1, layout.bottom() + 1, BORDER_COLOR);
		guiGraphics.fill(layout.left() - 1, layout.top(), layout.left(), layout.bottom(), BORDER_COLOR);
		guiGraphics.fill(layout.right(), layout.top(), layout.right() + 1, layout.bottom(), BORDER_COLOR);

		PoseStack poseStack = guiGraphics.pose();
		poseStack.pushPose();
		poseStack.translate(layout.textX(), layout.textY(), 0.0F);
		poseStack.scale(scale, scale, 1.0F);
		float currentY = 0.0F;
		for (String line : lines) {
			float lineStartX = (maxLineWidth - font.width(line)) / 2.0F;
			guiGraphics.drawString(font, line, Math.round(lineStartX), Math.round(currentY), textColor, false);
			currentY += font.lineHeight + LinkSerialHudOverlayLayoutSupport.LINE_SPACING;
		}
		poseStack.popPose();
	}
}
