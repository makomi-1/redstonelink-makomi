package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 定向面编辑器网络注册壳。
 */
final class DirectionalFaceEditorNetworkRegistrationSupport {
	private DirectionalFaceEditorNetworkRegistrationSupport() {
	}

	/**
	 * 注册全部 payload 与服务端接包器。
	 */
	static void register() {
		PayloadTypeRegistry.playC2S().register(
			DirectionalFaceEditorNetwork.CycleDirectionalFaceEditorModePayload.TYPE,
			DirectionalFaceEditorNetwork.CycleDirectionalFaceEditorModePayload.CODEC
		);

		ServerPlayNetworking.registerGlobalReceiver(
			DirectionalFaceEditorNetwork.CycleDirectionalFaceEditorModePayload.TYPE,
			(payload, context) -> {
				ServerPlayer player = context.player();
				if (player == null) {
					return;
				}
				player.level().getServer().execute(() -> DirectionalFaceEditorNetworkServerHandlerSupport.handleCycleMode(player));
			}
		);
	}
}
