package com.makomi.client.network;

import com.makomi.client.bench.BenchClientCommandBridge;
import com.makomi.network.BenchCommandNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * `BenchCommandNetwork` 的客户端接包壳。
 */
public final class BenchCommandNetworkClientHandlerSupport {
	private BenchCommandNetworkClientHandlerSupport() {
	}

	/**
	 * 注册 bench 命令桥客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(BenchCommandNetwork.PlayerCommandResultPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> BenchClientCommandBridge.handleCommandResponse(payload));
		});
	}
}
