package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 转发器网络注册壳。
 */
final class RepeaterNetworkRegistrationSupport {
	private RepeaterNetworkRegistrationSupport() {
	}

	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	private static void registerPayloadTypes() {
		PayloadTypeRegistry.clientboundPlay().register(
			RepeaterNetwork.OpenRepeaterEditorPayload.TYPE,
			RepeaterNetwork.OpenRepeaterEditorPayload.CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
			RepeaterNetwork.SaveRepeaterPayload.TYPE,
			RepeaterNetwork.SaveRepeaterPayload.CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
			RepeaterNetwork.OpenRepeaterPairingPayload.TYPE,
			RepeaterNetwork.OpenRepeaterPairingPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
			RepeaterNetwork.RepeaterFeedbackPayload.TYPE,
			RepeaterNetwork.RepeaterFeedbackPayload.CODEC
		);
	}

	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(RepeaterNetwork.SaveRepeaterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> RepeaterNetworkServerHandlerSupport.handleSaveRepeater(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(RepeaterNetwork.OpenRepeaterPairingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> RepeaterNetworkServerHandlerSupport.handleOpenRepeaterPairing(player, payload));
		});
	}
}
