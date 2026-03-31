package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 状态面板工具网络注册壳。
 */
final class StatePanelNetworkRegistrationSupport {
	private StatePanelNetworkRegistrationSupport() {
	}

	/**
	 * 注册全部 payload 与服务端接包器。
	 */
	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	/**
	 * 注册 C2S / S2C payload 类型。
	 */
	private static void registerPayloadTypes() {
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.OpenStatePanelPayload.TYPE,
			StatePanelNetwork.OpenStatePanelPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.SubscribeStatePanelPayload.TYPE,
			StatePanelNetwork.SubscribeStatePanelPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.RefreshStatePanelPayload.TYPE,
			StatePanelNetwork.RefreshStatePanelPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.RemoveStatePanelSerialPayload.TYPE,
			StatePanelNetwork.RemoveStatePanelSerialPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.RecordStatePanelPayload.TYPE,
			StatePanelNetwork.RecordStatePanelPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.CleanAllStatePanelPayload.TYPE,
			StatePanelNetwork.CleanAllStatePanelPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.StatePanelSnapshotPayload.TYPE,
			StatePanelNetwork.StatePanelSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.StatePanelFeedbackPayload.TYPE,
			StatePanelNetwork.StatePanelFeedbackPayload.CODEC
		);
	}

	/**
	 * 注册服务端接包器，并统一切回主线程处理。
	 */
	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.SubscribeStatePanelPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleSubscribe(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.RefreshStatePanelPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleRefresh(player));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.RemoveStatePanelSerialPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleRemove(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.RecordStatePanelPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleRecord(player));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.CleanAllStatePanelPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleCleanAll(player));
		});
	}
}