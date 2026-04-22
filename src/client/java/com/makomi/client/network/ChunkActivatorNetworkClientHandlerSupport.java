package com.makomi.client.network;

import com.makomi.client.screen.ChunkActivatorEditorScreen;
import com.makomi.network.ChunkActivatorNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 区块激活器网络客户端接包壳。
 */
public final class ChunkActivatorNetworkClientHandlerSupport {
	private ChunkActivatorNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部区块激活器客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(ChunkActivatorNetwork.OpenChunkActivatorEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(ChunkActivatorNetwork.ChunkActivatorFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> showFeedback(payload.messageKey(), payload.messageArgs()));
		});
	}

	private static void openEditor(ChunkActivatorNetwork.OpenChunkActivatorEditorPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || payload == null) {
			return;
		}
		minecraft.setScreen(
			new ChunkActivatorEditorScreen(
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.displayAlias(),
				payload.configStateSnapshot()
			)
		);
	}

	private static void showFeedback(String messageKey, java.util.List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.player.displayClientMessage(
			Component.translatable(messageKey, (messageArgs == null ? java.util.List.<String>of() : messageArgs).toArray()),
			false
		);
	}
}
