package com.makomi.client.render;

import net.minecraft.ChatFormatting;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 快速连接工具结果反馈辅助器。
 */
public final class QuickLinkFeedbackOverlayRenderer {
	private QuickLinkFeedbackOverlayRenderer() {
	}

	/**
	 * 在 action bar 显示最近一次 quick-link 结果提示。
	 */
	public static void showFeedback(boolean successState, String messageKey, List<String> messageArgs) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		Component message = buildMessage(messageKey, messageArgs);
		if (message.getString().isEmpty()) {
			return;
		}
		minecraft.player.displayClientMessage(
			message.copy().withStyle(successState ? ChatFormatting.GREEN : ChatFormatting.RED),
			true
		);
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
