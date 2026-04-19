package com.makomi.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
		ServerTickEvents.END_SERVER_TICK.register(StatePanelNetworkServerHandlerSupport::handleAutoStopTick);
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
			StatePanelNetwork.QueryStatePanelRecordingPayload.TYPE,
			StatePanelNetwork.QueryStatePanelRecordingPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.StartStatePanelRecordingPayload.TYPE,
			StatePanelNetwork.StartStatePanelRecordingPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.StopStatePanelRecordingPayload.TYPE,
			StatePanelNetwork.StopStatePanelRecordingPayload.CODEC
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
			StatePanelNetwork.ExportStatePanelGraphPayload.TYPE,
			StatePanelNetwork.ExportStatePanelGraphPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.SubmitGraphWritePayload.TYPE,
			StatePanelNetwork.SubmitGraphWritePayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			StatePanelNetwork.PreviewGraphWritePayload.TYPE,
			StatePanelNetwork.PreviewGraphWritePayload.CODEC
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
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.StatePanelRecordingSessionPayload.TYPE,
			StatePanelNetwork.StatePanelRecordingSessionPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.StatePanelRecordingExportChunkPayload.TYPE,
			StatePanelNetwork.StatePanelRecordingExportChunkPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.StatePanelGraphExportChunkPayload.TYPE,
			StatePanelNetwork.StatePanelGraphExportChunkPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			StatePanelNetwork.GraphWriteResultPayload.TYPE,
			StatePanelNetwork.GraphWriteResultPayload.CODEC
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
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.QueryStatePanelRecordingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleQueryRecordingSession(player));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StartStatePanelRecordingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleStartRecording(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StopStatePanelRecordingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleStopRecording(player));
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
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.ExportStatePanelGraphPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleExportGraph(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.SubmitGraphWritePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handleSubmitGraphWrite(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(StatePanelNetwork.PreviewGraphWritePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> StatePanelNetworkServerHandlerSupport.handlePreviewGraphWrite(player, payload));
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
