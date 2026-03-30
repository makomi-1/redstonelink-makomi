package com.makomi.client.network;

import com.makomi.client.screen.StatePanelToolScreen;
import com.makomi.network.StatePanelNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 状态面板工具客户端接包处理壳。
 */
public final class StatePanelNetworkClientHandlerSupport {
	private StatePanelNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.OpenStatePanelPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openPanel(payload.subscriptions()));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelSnapshotPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applySnapshot(payload.entries()));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applyFeedback(payload.success(), payload.messageKey(), payload.messageArgs()));
		});
	}

	/**
	 * 打开状态面板界面。
	 */
	private static void openPanel(java.util.List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.setScreen(new StatePanelToolScreen(subscriptions));
	}

	/**
	 * 将服务端快照应用到当前状态面板界面。
	 */
	private static void applySnapshot(java.util.List<StatePanelNetwork.StatePanelSnapshotEntry> entries) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof StatePanelToolScreen statePanelToolScreen) {
			statePanelToolScreen.applySnapshot(entries);
		}
	}

	/**
	 * 将服务端反馈应用到当前状态面板界面；若界面未打开则走 action bar。
	 */
	private static void applyFeedback(boolean success, String messageKey, java.util.List<String> messageArgs) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof StatePanelToolScreen statePanelToolScreen) {
			statePanelToolScreen.applyFeedback(success, messageKey, messageArgs);
			return;
		}
		if (minecraft.player == null || messageKey == null || messageKey.isBlank()) {
			return;
		}
		Object[] args = messageArgs == null ? new Object[0] : messageArgs.toArray();
		minecraft.player.displayClientMessage(Component.translatable(messageKey, args), true);
	}
}