package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 智能节点容器网络注册壳。
 */
final class SmartNodeContainerNetworkRegistrationSupport {
	private SmartNodeContainerNetworkRegistrationSupport() {
	}

	/**
	 * 注册全部 payload 与服务端接包器。
	 */
	static void register() {
		PayloadTypeRegistry.playC2S().register(
			SmartNodeContainerNetwork.OpenSmartNodeContainerPayload.TYPE,
			SmartNodeContainerNetwork.OpenSmartNodeContainerPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			SmartNodeContainerNetwork.CycleSmartNodeContainerTypePayload.TYPE,
			SmartNodeContainerNetwork.CycleSmartNodeContainerTypePayload.CODEC
		);

		ServerPlayNetworking.registerGlobalReceiver(SmartNodeContainerNetwork.OpenSmartNodeContainerPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.getServer().execute(() -> SmartNodeContainerNetworkServerHandlerSupport.handleOpen(player));
		});
		ServerPlayNetworking.registerGlobalReceiver(SmartNodeContainerNetwork.CycleSmartNodeContainerTypePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.getServer().execute(() -> SmartNodeContainerNetworkServerHandlerSupport.handleCycleSelectedType(player));
		});
	}
}
