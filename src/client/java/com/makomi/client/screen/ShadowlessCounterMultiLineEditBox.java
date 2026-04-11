package com.makomi.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 去除容量计数字体阴影的多行输入框基类。
 * <p>
 * 保留原版多行输入框的文本、滚动、选择和光标行为，
 * 只覆写装饰层，让容量计数改为平面字，同时提供可覆写钩子，
 * 供不同 GUI 在此基础上继续定制背景和计数颜色。
 * </p>
 */
class ShadowlessCounterMultiLineEditBox extends MultiLineEditBox {
	private static final ResourceLocation SCROLLER_SPRITE = ResourceLocation.withDefaultNamespace("widget/scroller");
	private static final int COUNTER_TEXT_COLOR = 0xA0A0A0;

	private final Font font;
	private int characterLimit = -1;

	ShadowlessCounterMultiLineEditBox(
		Font font,
		int x,
		int y,
		int width,
		int height,
		Component message,
		Component placeholder
	) {
		super(font, x, y, width, height, message, placeholder);
		this.font = font;
	}

	@Override
	public void setCharacterLimit(int limit) {
		characterLimit = limit;
		super.setCharacterLimit(limit);
	}

	@Override
	protected void renderDecorations(GuiGraphics guiGraphics) {
		renderScrollBar(guiGraphics);
		renderCharacterLimitCounter(guiGraphics);
	}

	/**
	 * 绘制原版风格滚动条，避免覆写后丢失滚动反馈。
	 */
	protected void renderScrollBar(GuiGraphics guiGraphics) {
		if (!scrollbarVisible()) {
			return;
		}

		int scrollerHeight = Mth.clamp((int) ((float) getHeight() * getHeight() / (float) getContentHeight()), 32, getHeight());
		int scrollerLeft = getX() + getWidth();
		int maxScrollAmount = getMaxScrollAmount();
		int scrollerTop = maxScrollAmount <= 0
			? getY()
			: Math.max(getY(), (int) scrollAmount() * (getHeight() - scrollerHeight) / maxScrollAmount + getY());

		RenderSystem.enableBlend();
		try {
			guiGraphics.blitSprite(SCROLLER_SPRITE, scrollerLeft, scrollerTop, scrollbarWidth(), scrollerHeight);
		} finally {
			RenderSystem.disableBlend();
		}
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
		guiGraphics.drawString(font, counterText, counterX, counterY, counterTextColor(), false);
	}

	/**
	 * @return 容量计数字体颜色，默认沿用原版灰色
	 */
	protected int counterTextColor() {
		return COUNTER_TEXT_COLOR;
	}

	/**
	 * 复刻原版内容高度计算，保证滚动条比例与原生一致。
	 */
	private int getContentHeight() {
		return getInnerHeight() + 4;
	}
}
