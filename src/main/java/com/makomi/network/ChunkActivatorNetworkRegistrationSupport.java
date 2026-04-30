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
		PayloadTypeRegistry.clientboundPlay().register(
			ChunkActivatorNetwork.OpenChunkActivatorEditorPayload.TYPE,
			ChunkActivatorNetwork.OpenChunkActivatorEditorPayload.CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
			ChunkActivatorNetwork.SaveChunkActivatorPayload.TYPE,
			ChunkActivatorNetwork.SaveChunkActivatorPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
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
