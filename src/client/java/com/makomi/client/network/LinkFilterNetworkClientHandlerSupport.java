package com.makomi.client.network;

import com.makomi.client.screen.LinkFilterEditorScreen;
import com.makomi.network.LinkFilterNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 过滤器网络客户端接包壳。
 */
public final class LinkFilterNetworkClientHandlerSupport {
	private LinkFilterNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部过滤器客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(LinkFilterNetwork.OpenFilterEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(LinkFilterNetwork.FilterFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> showFeedback(payload.messageKey(), payload.messageArgs()));
		});
	}

	/**
	 * 打开过滤器编辑界面。
	 */
	private static void openEditor(LinkFilterNetwork.OpenFilterEditorPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || payload == null) {
			return;
		}
		minecraft.setScreen(
			new LinkFilterEditorScreen(
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.filterKind(),
				payload.displayAlias(),
				payload.configSnapshot()
			)
		);
	}

	/**
	 * 将服务端反馈展示到客户端聊天栏。
	 */
	private static void showFeedback(String messageKey, java.util.List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.player.displayClientMessage(Component.translatable(messageKey, (messageArgs == null ? java.util.List.<String>of() : messageArgs).toArray()), false);
	}
}
