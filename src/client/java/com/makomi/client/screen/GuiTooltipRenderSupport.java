package com.makomi.client.screen;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * GUI tooltip 渲染兼容支持。
 * <p>
 * 统一把 `Component` 列表转换为 1.21.11 需要的 `ClientTooltipComponent`，
 * 避免各个 screen 分散适配 tooltip 新签名。
 * </p>
 */
final class GuiTooltipRenderSupport {
	private GuiTooltipRenderSupport() {
	}

	/**
	 * 直接按当前帧渲染组件 tooltip。
	 */
	static void renderComponentTooltip(GuiGraphicsExtractor guiGraphics, Font font, List<Component> tooltipLines, int mouseX, int mouseY) {
		if (guiGraphics == null || font == null || tooltipLines == null || tooltipLines.isEmpty()) {
			return;
		}
		guiGraphics.setComponentTooltipForNextFrame(font, tooltipLines, mouseX, mouseY);
	}
}
