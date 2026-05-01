package com.makomi.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 可复用的主题化单行输入框。
 * <p>
 * 通过外层自绘背景与内层原版 `EditBox` 组合，实现主题色同步，
 * 同时尽量保留原版文本编辑与光标逻辑。
 * </p>
 */
final class StyledEditBox extends EditBox {
	private static final int INNER_HORIZONTAL_PADDING = 4;
	private static final int INNER_VERTICAL_PADDING = 2;

	private final Style style;
	private int outerX;
	private int outerY;
	private int outerWidth;
	private int outerHeight;

	StyledEditBox(Font font, int x, int y, int width, int height, Component message, Style style) {
		super(
			font,
			x + INNER_HORIZONTAL_PADDING,
			y + INNER_VERTICAL_PADDING,
			Math.max(1, width - (INNER_HORIZONTAL_PADDING * 2)),
			Math.max(1, height - (INNER_VERTICAL_PADDING * 2)),
			message
		);
		this.style = style == null ? Style.defaultStyle() : style;
		this.outerX = x;
		this.outerY = y;
		this.outerWidth = width;
		this.outerHeight = height;
		setBordered(false);
		setTextShadow(false);
		setTextColor(this.style.textColor());
		setTextColorUneditable(this.style.disabledTextColor());
	}

	@Override
	public void setX(int x) {
		outerX = x;
		super.setX(x + INNER_HORIZONTAL_PADDING);
	}

	@Override
	public void setY(int y) {
		outerY = y;
		super.setY(y + INNER_VERTICAL_PADDING);
	}

	@Override
	public void setWidth(int width) {
		outerWidth = width;
		super.setWidth(Math.max(1, width - (INNER_HORIZONTAL_PADDING * 2)));
	}

	@Override
	public void setHeight(int height) {
		outerHeight = height;
		super.setHeight(Math.max(1, height - (INNER_VERTICAL_PADDING * 2)));
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		VisualColors colors = resolveVisualColors();
		guiGraphics.fill(outerX, outerY, outerX + outerWidth, outerY + outerHeight, withAlpha(colors.borderColor()));
		guiGraphics.fill(
			outerX + 1,
			outerY + 1,
			outerX + outerWidth - 1,
			outerY + outerHeight - 1,
			withAlpha(colors.backgroundColor())
		);
		setTextColor(withAlpha(colors.textColor()));
		setTextColorUneditable(withAlpha(colors.disabledTextColor()));
		super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
	}

	/**
	 * 按当前聚焦/禁用状态解析视觉颜色。
	 */
	private VisualColors resolveVisualColors() {
		if (!active) {
			return new VisualColors(style.disabledBackgroundColor(), style.disabledBorderColor(), style.disabledTextColor(), style.disabledTextColor());
		}
		int borderColor = isFocused() ? style.focusedBorderColor() : style.borderColor();
		return new VisualColors(style.backgroundColor(), borderColor, style.textColor(), style.disabledTextColor());
	}

	/**
	 * 将控件透明度乘到最终颜色 alpha 上，和其它自绘控件保持一致。
	 */
	private int withAlpha(int argbColor) {
		int baseAlpha = (argbColor >>> 24) & 0xFF;
		int mixedAlpha = Mth.clamp(Math.round(baseAlpha * alpha), 0, 255);
		return (mixedAlpha << 24) | (argbColor & 0x00FFFFFF);
	}

	/**
	 * 输入框皮肤配置。
	 */
	record Style(
		int backgroundColor,
		int borderColor,
		int focusedBorderColor,
		int disabledBackgroundColor,
		int disabledBorderColor,
		int textColor,
		int disabledTextColor
	) {
		/**
		 * 默认皮肤尽量贴近原版深色输入框。
		 */
		static Style defaultStyle() {
			return new Style(0xFF202020, 0xFF5A5A5A, 0xFFFFFFFF, 0xFF2A2A2A, 0xFF5A5A5A, 0xFFFFFFFF, 0xFFA0A0A0);
		}
	}

	/**
	 * 当前绘制阶段的最终颜色集合。
	 */
	private record VisualColors(int backgroundColor, int borderColor, int textColor, int disabledTextColor) {
	}
}
