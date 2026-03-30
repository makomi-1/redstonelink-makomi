package com.makomi.client.render;

import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 快速连接工具结果 HUD 渲染器。
 * <p>
 * 在屏幕中心短暂显示最近一次应用结果，替代聊天栏回显。
 * </p>
 */
public final class QuickLinkFeedbackOverlayRenderer {
	private static final long DISPLAY_DURATION_MILLIS = 2200L;
	private static Component message = Component.empty();
	private static boolean success;
	private static long expireAtMillis;

	private QuickLinkFeedbackOverlayRenderer() {
	}

	/**
	 * 写入最近一次结果提示。
	 */
	public static void showFeedback(boolean successState, String messageKey, List<String> messageArgs) {
		message = buildMessage(messageKey, messageArgs);
		success = successState;
		expireAtMillis = System.currentTimeMillis() + DISPLAY_DURATION_MILLIS;
	}

	/**
	 * 在 HUD 层绘制结果提示。
	 */
	public static void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
		if (message.getString().isEmpty() || System.currentTimeMillis() > expireAtMillis) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		int color = success ? 0x7CFF7C : 0xFF7070;
		int centerX = guiGraphics.guiWidth() / 2;
		int centerY = guiGraphics.guiHeight() / 2 - 30;
		guiGraphics.drawCenteredString(minecraft.font, message, centerX, centerY, color);
	}

	/**
	 * 将 payload 中的 key + args 组装为最终客户端文本。
	 */
	private static Component buildMessage(String messageKey, List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			return Component.empty();
		}
		Object[] args = (messageArgs == null ? List.<String>of() : messageArgs).toArray(Object[]::new);
		return Component.translatable(messageKey, args);
	}
}
