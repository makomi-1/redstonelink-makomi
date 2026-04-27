package com.makomi.client.render;

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
		drawCenteredWithPanel(guiGraphics, font, lines, textColor, scale, PanelStyle.DEFAULT);
	}

	/**
	 * 按节点类型解析近 HUD 面板主题。
	 */
	static PanelStyle resolveNodePanelStyle(com.makomi.data.LinkNodeType nodeType) {
		if (nodeType == com.makomi.data.LinkNodeType.CORE) {
			return PanelStyle.CORE;
		}
		if (nodeType == com.makomi.data.LinkNodeType.TRIGGER_SOURCE) {
			return PanelStyle.TRIGGER_SOURCE;
		}
		return PanelStyle.DEFAULT;
	}

	/**
	 * 过滤器 HUD 使用独立粉色主题。
	 */
	static PanelStyle resolveFilterPanelStyle() {
		return PanelStyle.FILTER;
	}

	/**
	 * 区块激活器 HUD 使用独立紫色主题。
	 */
	static PanelStyle resolveChunkActivatorPanelStyle() {
		return PanelStyle.CHUNK_ACTIVATOR;
	}

	/**
	 * 在屏幕中心按指定面板主题绘制文本。
	 */
	static void drawCenteredWithPanel(
		GuiGraphics guiGraphics,
		Font font,
		List<String> lines,
		int textColor,
		float scale,
		PanelStyle panelStyle
	) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		PanelStyle resolvedPanelStyle = panelStyle == null ? PanelStyle.DEFAULT : panelStyle;
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

		guiGraphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), resolvedPanelStyle.backgroundColor());
		guiGraphics.fill(
			layout.left() - 1,
			layout.top() - 1,
			layout.right() + 1,
			layout.top(),
			resolvedPanelStyle.borderColor()
		);
		guiGraphics.fill(
			layout.left() - 1,
			layout.bottom(),
			layout.right() + 1,
			layout.bottom() + 1,
			resolvedPanelStyle.borderColor()
		);
		guiGraphics.fill(
			layout.left() - 1,
			layout.top(),
			layout.left(),
			layout.bottom(),
			resolvedPanelStyle.borderColor()
		);
		guiGraphics.fill(
			layout.right(),
			layout.top(),
			layout.right() + 1,
			layout.bottom(),
			resolvedPanelStyle.borderColor()
		);

		var poseStack = guiGraphics.pose();
		poseStack.pushMatrix();
		poseStack.translate(layout.textX(), layout.textY());
		poseStack.scale(scale, scale);
		float currentY = 0.0F;
		for (String line : lines) {
			float lineStartX = (maxLineWidth - font.width(line)) / 2.0F;
			guiGraphics.drawString(font, line, Math.round(lineStartX), Math.round(currentY), textColor, false);
			currentY += font.lineHeight + LinkSerialHudOverlayLayoutSupport.LINE_SPACING;
		}
		poseStack.popMatrix();
	}

	/**
	 * HUD 面板配色预设。
	 */
	enum PanelStyle {
		DEFAULT(0xD012141A, 0xA0363E4D),
		CORE(0xD0071622, 0xE05DD7FF),
		TRIGGER_SOURCE(0xD01A1005, 0xE0FFC66A),
		FILTER(0xD01C0811, 0xE0FF7AA8),
		CHUNK_ACTIVATOR(0xD0120A1E, 0xE0B36DE9),
		REPEATER(0xD008190A, 0xE096FF9F);

		private final int backgroundColor;
		private final int borderColor;

		PanelStyle(int backgroundColor, int borderColor) {
			this.backgroundColor = backgroundColor;
			this.borderColor = borderColor;
		}

		int backgroundColor() {
			return backgroundColor;
		}

		int borderColor() {
			return borderColor;
		}
	}
}
