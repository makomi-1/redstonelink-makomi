package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 快速连接工具网络注册壳。
 */
final class QuickLinkNetworkRegistrationSupport {
	private QuickLinkNetworkRegistrationSupport() {
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
			QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE,
			QuickLinkNetwork.OpenQuickLinkEditorPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.SaveQuickLinkPayload.TYPE,
			QuickLinkNetwork.SaveQuickLinkPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.CollectQuickLinkPayload.TYPE,
			QuickLinkNetwork.CollectQuickLinkPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.RequestQuickLinkChannelPreviewPayload.TYPE,
			QuickLinkNetwork.RequestQuickLinkChannelPreviewPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.RequestQuickLinkVisualizeSnapshotPayload.TYPE,
			QuickLinkNetwork.RequestQuickLinkVisualizeSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload.TYPE,
			QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload.CODEC
		);
		PayloadTypeRegistry.playC2S().register(
			QuickLinkNetwork.ApplyQuickLinkPayload.TYPE,
			QuickLinkNetwork.ApplyQuickLinkPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			QuickLinkNetwork.ApplyQuickLinkBaselinePayload.TYPE,
			QuickLinkNetwork.ApplyQuickLinkBaselinePayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			QuickLinkNetwork.QuickLinkChannelPreviewPayload.TYPE,
			QuickLinkNetwork.QuickLinkChannelPreviewPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload.TYPE,
			QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.playS2C().register(
			QuickLinkNetwork.QuickLinkFeedbackPayload.TYPE,
			QuickLinkNetwork.QuickLinkFeedbackPayload.CODEC
		);
	}

	/**
	 * 注册服务端接包器，并统一切回主线程处理。
	 */
	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.SaveQuickLinkPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleSaveQuickLink(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.CollectQuickLinkPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleCollectQuickLink(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.RequestQuickLinkChannelPreviewPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleRequestQuickLinkChannelPreview(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.RequestQuickLinkVisualizeSnapshotPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleRequestQuickLinkVisualizeSnapshot(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleRequestApplyQuickLinkBaseline(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.ApplyQuickLinkPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> QuickLinkNetworkServerHandlerSupport.handleApplyQuickLink(player, payload));
		});
	}
}
