package com.makomi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 客户端本地提示消息显示适配。
 * <p>
 * 26.1 起 `LocalPlayer#displayClientMessage(...)` 已移除，
 * 这里统一按“action bar / 普通系统消息”两类语义做迁移适配。
 * </p>
 */
public final class ClientMessageDisplaySupport {
	private ClientMessageDisplaySupport() {
	}

	/**
	 * 按原有 `displayClientMessage` 语义显示本地提示。
	 *
	 * @param actionBar `true` 表示显示到 action bar，`false` 表示写入普通系统消息
	 */
	public static void show(Minecraft minecraft, Component message, boolean actionBar) {
		if (minecraft == null || minecraft.player == null || message == null) {
			return;
		}
		if (actionBar) {
			minecraft.gui.setOverlayMessage(message, false);
			return;
		}
		minecraft.player.sendSystemMessage(message);
	}
}
