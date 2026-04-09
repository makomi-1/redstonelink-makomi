package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 过滤器网络注册壳。
 */
final class LinkFilterNetworkRegistrationSupport {
	private LinkFilterNetworkRegistrationSupport() {
	}

	/**
	 * 注册全部 payload 与服务端接包器。
	 */
	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	/**
	 * 注册全部 C2S/S2C payload 类型。
	 */
	private static void registerPayloadTypes() {
		PayloadTypeRegistry.playS2C().register(
			LinkFilterNetwork.OpenFilterEditorPayload.TYPE,
			LinkFilterNetwork.OpenFilterEditorPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(LinkFilterNetwork.SaveFilterPayload.TYPE, LinkFilterNetwork.SaveFilterPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(
			LinkFilterNetwork.FilterFeedbackPayload.TYPE,
			LinkFilterNetwork.FilterFeedbackPayload.CODEC
		);
	}

	/**
	 * 注册服务端过滤器保存接包器。
	 */
	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(LinkFilterNetwork.SaveFilterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> LinkFilterNetworkServerHandlerSupport.handleSaveFilter(player, payload));
		});
	}
}
