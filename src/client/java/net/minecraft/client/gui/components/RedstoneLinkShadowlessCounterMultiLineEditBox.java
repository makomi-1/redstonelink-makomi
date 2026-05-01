package net.minecraft.client.gui.components;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * RedstoneLink 专用的多行输入框兼容层。
 * <p>
 * 1.21.11 版本下，外部包无法再直接沿用旧版 `MultiLineEditBox` 子类化入口，
 * 因此这里在同包内补一个薄兼容层，继续保留原版的文本、滚动、选区与光标逻辑，
 * 只接管装饰层，把容量计数字体改为无阴影。
 * </p>
 */
public class RedstoneLinkShadowlessCounterMultiLineEditBox extends MultiLineEditBox {
	private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
	private static final int DEFAULT_COUNTER_TEXT_COLOR = 0xFFA0A0A0;

	private final Font font;
	private int characterLimit = -1;

	/**
	 * 创建一个保留原版编辑行为、但改为无阴影计数的多行输入框。
	 */
	public RedstoneLinkShadowlessCounterMultiLineEditBox(
		Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder
	) {
		super(font, x, y, width, height, message, placeholder, DEFAULT_TEXT_COLOR, false, DEFAULT_TEXT_COLOR, true, true);
		this.font = font;
	}

	@Override
	public void setCharacterLimit(int limit) {
		characterLimit = limit;
		super.setCharacterLimit(limit);
	}

	@Override
	protected void renderDecorations(GuiGraphics guiGraphics) {
		super.renderDecorations(guiGraphics);
		renderCharacterLimitCounter(guiGraphics);
	}

	/**
	 * 绘制无阴影容量计数。
	 */
	protected void renderCharacterLimitCounter(GuiGraphics guiGraphics) {
		if (characterLimit <= 0) {
			return;
		}
		Component counterText = Component.translatable("gui.multiLineEditBox.character_limit", getValue().length(), characterLimit);
		int counterX = getX() + getWidth() - font.width(counterText);
		int counterY = getY() + getHeight() + 4;
		guiGraphics.drawString(font, counterText, counterX, counterY, withAlpha(counterTextColor()), false);
	}

	/**
	 * @return 容量计数字体颜色；主题化子类可覆写
	 */
	protected int counterTextColor() {
		return DEFAULT_COUNTER_TEXT_COLOR;
	}

	/**
	 * 将控件透明度乘到最终颜色 alpha 上，和其它自绘控件保持一致。
	 */
	protected final int withAlpha(int argbColor) {
		int baseAlpha = (argbColor >>> 24) & 0xFF;
		int mixedAlpha = Mth.clamp(Math.round(baseAlpha * this.alpha), 0, 255);
		return (mixedAlpha << 24) | (argbColor & 0x00FFFFFF);
	}
}
