package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * `BenchCommandNetwork` 的注册壳。
 */
final class BenchCommandNetworkRegistrationSupport {
	private BenchCommandNetworkRegistrationSupport() {
	}

	/**
	 * 注册 payload 类型与服务端接包器。
	 */
	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	/**
	 * 注册 bench 命令桥使用的 C2S / S2C payload。
	 */
	private static void registerPayloadTypes() {
		PayloadTypeRegistry.playC2S().register(
			BenchCommandNetwork.ExecutePlayerCommandPayload.TYPE,
			BenchCommandNetwork.ExecutePlayerCommandPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			BenchCommandNetwork.PlayerCommandResultPayload.TYPE,
			BenchCommandNetwork.PlayerCommandResultPayload.CODEC
		);
	}

	/**
	 * 注册服务端接包器，并统一切回主线程后执行玩家命令。
	 */
	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(BenchCommandNetwork.ExecutePlayerCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.getServer().execute(() -> BenchCommandNetworkServerHandlerSupport.handleExecutePlayerCommand(player, payload));
		});
	}
}
