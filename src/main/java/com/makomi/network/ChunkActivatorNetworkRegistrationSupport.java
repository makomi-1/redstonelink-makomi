package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 区块激活器网络注册壳。
 */
final class ChunkActivatorNetworkRegistrationSupport {
	private ChunkActivatorNetworkRegistrationSupport() {
	}

	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	private static void registerPayloadTypes() {
		PayloadTypeRegistry.playS2C().register(
			ChunkActivatorNetwork.OpenChunkActivatorEditorPayload.TYPE,
			ChunkActivatorNetwork.OpenChunkActivatorEditorPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			ChunkActivatorNetwork.SaveChunkActivatorPayload.TYPE,
			ChunkActivatorNetwork.SaveChunkActivatorPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			ChunkActivatorNetwork.ChunkActivatorFeedbackPayload.TYPE,
			ChunkActivatorNetwork.ChunkActivatorFeedbackPayload.CODEC
		);
	}

	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(ChunkActivatorNetwork.SaveChunkActivatorPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> ChunkActivatorNetworkServerHandlerSupport.handleSaveChunkActivator(player, payload));
		});
	}
}
