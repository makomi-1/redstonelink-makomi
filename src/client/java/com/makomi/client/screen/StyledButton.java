package com.makomi.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 可定制底色与描边的按钮。
 * <p>
 * 保留原版 `Button` 的点击、可访问性与交互语义，
 * 仅接管绘制层，供不同 GUI 通过覆写样式钩子复用同一套按钮皮肤能力。
 * </p>
 */
final class StyledButton extends Button {
	private final Style style;

	/**
	 * @param style 按钮皮肤配置；传入 `null` 时回退到默认深色按钮样式
	 */
	StyledButton(int x, int y, int width, int height, Component message, OnPress onPress, Style style) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.style = style == null ? Style.defaultStyle() : style;
	}

	@Override
	protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		VisualColors colors = resolveVisualColors();
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		guiGraphics.fill(left, top, right, bottom, withAlpha(colors.borderColor()));
		guiGraphics.fill(left + 1, top + 1, right - 1, bottom - 1, withAlpha(colors.backgroundColor()));

		int textColor = withAlpha(colors.textColor());
		int textY = top + (getHeight() - 8) / 2;
		guiGraphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), left + (getWidth() / 2), textY, textColor);
	}

	/**
	 * 按当前悬停/禁用状态解析视觉颜色。
	 */
	private VisualColors resolveVisualColors() {
		if (!active) {
			return new VisualColors(style.disabledBackgroundColor(), style.disabledBorderColor(), style.disabledTextColor());
		}
		if (isHoveredOrFocused()) {
			return new VisualColors(style.hoverBackgroundColor(), style.hoverBorderColor(), style.textColor());
		}
		return new VisualColors(style.backgroundColor(), style.borderColor(), style.textColor());
	}

	/**
	 * 将控件当前透明度乘到样式颜色上，保持和 `AbstractWidget#alpha` 一致。
	 */
	private int withAlpha(int argbColor) {
		int baseAlpha = (argbColor >>> 24) & 0xFF;
		int mixedAlpha = Mth.clamp(Math.round(baseAlpha * alpha), 0, 255);
		return (mixedAlpha << 24) | (argbColor & 0x00FFFFFF);
	}

	/**
	 * 按钮皮肤配置。
	 */
	record Style(
		int backgroundColor,
		int hoverBackgroundColor,
		int disabledBackgroundColor,
		int borderColor,
		int hoverBorderColor,
		int disabledBorderColor,
		int textColor,
		int disabledTextColor
	) {
		/**
		 * 默认皮肤尽量贴近原版深色按钮观感，供未覆写界面复用。
		 */
		static Style defaultStyle() {
			return new Style(0xFF3C3C3C, 0xFF4A4A4A, 0xFF2A2A2A, 0xFF7A7A7A, 0xFFFFFFFF, 0xFF5A5A5A, 0xFFFFFFFF, 0xFFA0A0A0);
		}
	}

	/**
	 * 绘制阶段的最终颜色集合。
	 */
	private record VisualColors(int backgroundColor, int borderColor, int textColor) {
	}
}
