package com.makomi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * `PairingNetwork` 的注册壳。
 * <p>
 * 统一承接 payload type 注册和服务端全局接包注册，避免主类继续膨胀。
 * </p>
 */
final class PairingNetworkRegistrationSupport {
	private PairingNetworkRegistrationSupport() {
	}

	/**
	 * 注册 `PairingNetwork` 所需的全部 payload 与服务端接包器。
	 */
	static void register() {
		registerPayloadTypes();
		registerServerReceivers();
	}

	/**
	 * 注册全部 C2S/S2C payload 类型。
	 */
	private static void registerPayloadTypes() {
		PayloadTypeRegistry.clientboundPlay().register(
			PairingNetwork.OpenTriggerSourcePairingPayload.TYPE,
			PairingNetwork.OpenTriggerSourcePairingPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(PairingNetwork.OpenCorePairingPayload.TYPE, PairingNetwork.OpenCorePairingPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PairingNetwork.SubmitTriggerSourcePairingPayload.TYPE,
			PairingNetwork.SubmitTriggerSourcePairingPayload.CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(PairingNetwork.SubmitCorePairingPayload.TYPE, PairingNetwork.SubmitCorePairingPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PairingNetwork.SubmitPairingAliasPayload.TYPE, PairingNetwork.SubmitPairingAliasPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PairingNetwork.SaveSyncLinkerSignalStrengthPayload.TYPE,
			PairingNetwork.SaveSyncLinkerSignalStrengthPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(PairingNetwork.PairingFeedbackPayload.TYPE, PairingNetwork.PairingFeedbackPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PairingNetwork.PairingAliasStatePayload.TYPE, PairingNetwork.PairingAliasStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PairingNetwork.RequestCurrentLinksPayload.TYPE,
			PairingNetwork.RequestCurrentLinksPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
			PairingNetwork.CurrentLinksSnapshotPayload.TYPE,
			PairingNetwork.CurrentLinksSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
			PairingNetwork.RequestRuntimeHudSnapshotPayload.TYPE,
			PairingNetwork.RequestRuntimeHudSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
			PairingNetwork.RuntimeHudSnapshotPayload.TYPE,
			PairingNetwork.RuntimeHudSnapshotPayload.CODEC
		);
	}

	/**
	 * 注册服务端全局接包器，并在切回主线程后委托给 server handler helper。
	 */
	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.SubmitTriggerSourcePairingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleSubmitTriggerSourcePairing(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.SubmitCorePairingPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleSubmitCorePairing(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.SubmitPairingAliasPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleSubmitPairingAlias(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.SaveSyncLinkerSignalStrengthPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleSaveSyncLinkerSignalStrength(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.RequestCurrentLinksPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleRequestCurrentLinks(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(PairingNetwork.RequestRuntimeHudSnapshotPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.level().getServer().execute(() -> PairingNetworkServerHandlerSupport.handleRequestRuntimeHudSnapshot(player, payload));
		});
	}
}
