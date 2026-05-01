package net.minecraft.client.gui.components;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * RedstoneLink 专用的主题化多行输入框。
 * <p>
 * 在保留 `RedstoneLinkShadowlessCounterMultiLineEditBox` 无阴影计数与原版编辑行为的前提下，
 * 仅接管主题背景层，实现与 1.21.1 版本一致的主题色表现。
 * </p>
 */
public final class RedstoneLinkStyledMultiLineEditBox extends RedstoneLinkShadowlessCounterMultiLineEditBox {
	private final int backgroundColor;
	private final int borderColor;
	private final int focusedBorderColor;
	private final int counterTextColor;

	/**
	 * 创建带主题壳的多行输入框。
	 */
	public RedstoneLinkStyledMultiLineEditBox(
		Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder,
		int backgroundColor,
		int borderColor,
		int focusedBorderColor,
		int counterTextColor
	) {
		super(font, x, y, width, height, message, placeholder);
		this.backgroundColor = backgroundColor;
		this.borderColor = borderColor;
		this.focusedBorderColor = focusedBorderColor;
		this.counterTextColor = counterTextColor;
	}

	@Override
	protected void renderBackground(GuiGraphics guiGraphics) {
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		int resolvedBorderColor = isFocused() ? focusedBorderColor : borderColor;
		guiGraphics.fill(left, top, right, bottom, withAlpha(resolvedBorderColor));
		guiGraphics.fill(left + 1, top + 1, right - 1, bottom - 1, withAlpha(backgroundColor));
	}

	@Override
	protected int counterTextColor() {
		return counterTextColor;
	}
}
