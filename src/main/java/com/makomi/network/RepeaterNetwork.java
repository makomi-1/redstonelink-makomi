package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterGraphSnapshotSupport;
import com.makomi.data.RepeaterItemData;
import com.makomi.util.SerialParseUtil;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 转发器编辑器网络通道。
 */
public final class RepeaterNetwork {
	public static final String INPUT_SIDE_TOKEN = "input";
	public static final String OUTPUT_SIDE_TOKEN = "output";

	private RepeaterNetwork() {
	}

	/**
	 * 注册全部 payload 与接包器。
	 */
	public static void register() {
		RepeaterNetworkRegistrationSupport.register();
	}

	/**
	 * 打开已放置转发器编辑器。
	 */
	public static void openEditor(ServerPlayer player, LinkRepeaterBlockEntity blockEntity) {
		if (player == null || blockEntity == null || !(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		long serial = blockEntity.getSerial();
		if (serial <= 0L) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			buildOpenEditorPayload(
				LinkFilterEditorTargetKind.BLOCK_ENTITY,
				serverLevel,
				serial,
				serverLevel.dimension().identifier().toString(),
				blockEntity.getBlockPos().asLong(),
				-1,
				"",
				blockEntity.snapshot()
			)
		);
	}

	/**
	 * 打开主手手持转发器编辑器。
	 */
	public static void openHeldItemEditor(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		long serial = RepeaterItemData.ensureSerial(stack, player.serverLevel());
		if (serial <= 0L) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			buildOpenEditorPayload(
				LinkFilterEditorTargetKind.HELD_MAIN_HAND,
				player.serverLevel(),
				serial,
				"",
				0L,
				player.getInventory().selected,
				LinkItemData.getDisplayAlias(stack),
				RepeaterItemData.read(stack)
			)
		);
	}

	private static OpenRepeaterEditorPayload buildOpenEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		ServerLevel level,
		long serial,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAliasFallback,
		RepeaterConfigSnapshot fallbackSnapshot
	) {
		LinkSavedData savedData = LinkSavedData.get(level);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(savedData, LinkNodeType.CORE, serial);
		RepeaterConfigSnapshot resolvedSnapshot = RepeaterGraphSnapshotSupport.resolve(level, serial, fallbackSnapshot);
		return new OpenRepeaterEditorPayload(
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			serial,
			RepeaterGraphSnapshotSupport.resolveAlias(level, serial, displayAliasFallback),
			resolvedSnapshot,
			RepeaterGraphSnapshotSupport.resolveInputDisplayTexts(level, serial),
			RepeaterGraphSnapshotSupport.resolveOutputDisplayTexts(level, serial),
			baseline.coreRevision(),
			savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, serial)
		);
	}

	private static String normalizePairingSide(String rawSideToken) {
		if (OUTPUT_SIDE_TOKEN.equalsIgnoreCase(rawSideToken)) {
			return OUTPUT_SIDE_TOKEN;
		}
		return INPUT_SIDE_TOKEN;
	}

	/**
	 * 服务端打开编辑器的 S2C 包。
	 */
	public record OpenRepeaterEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		List<String> inputDisplayTexts,
		List<String> outputDisplayTexts,
		long expectedCoreRevision,
		long expectedSourceRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenRepeaterEditorPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_repeater_editor")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenRepeaterEditorPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> RepeaterNetworkPayloadSupport.encodeOpenEditorPayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.serial(),
				payload.displayAlias(),
				payload.configSnapshot(),
				payload.inputDisplayTexts(),
				payload.outputDisplayTexts(),
				payload.expectedCoreRevision(),
				payload.expectedSourceRevision()
			),
			buffer -> {
				RepeaterNetworkPayloadSupport.DecodedOpenEditorPayload decoded = RepeaterNetworkPayloadSupport.decodeOpenEditorPayload(
					buffer
				);
				return new OpenRepeaterEditorPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.serial(),
					decoded.displayAlias(),
					decoded.configSnapshot(),
					decoded.inputDisplayTexts(),
					decoded.outputDisplayTexts(),
					decoded.expectedCoreRevision(),
					decoded.expectedSourceRevision()
				);
			}
		);

		public OpenRepeaterEditorPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			serial = Math.max(0L, serial);
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configSnapshot = configSnapshot == null ? RepeaterConfigSnapshot.empty() : configSnapshot;
			inputDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(
				SerialParseUtil.parseTargetsOrdered(configSnapshot.inputSerialExpression(), 0).orderedTargets(),
				inputDisplayTexts
			);
			outputDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(
				SerialParseUtil.parseTargetsOrdered(configSnapshot.outputSerialExpression(), 0).orderedTargets(),
				outputDisplayTexts
			);
			expectedCoreRevision = Math.max(0L, expectedCoreRevision);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端提交转发器配置的 C2S 请求。
	 */
	public record SaveRepeaterPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SaveRepeaterPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "save_repeater")
		);
		public static final StreamCodec<FriendlyByteBuf, SaveRepeaterPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> RepeaterNetworkPayloadSupport.encodeSavePayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.serial(),
				payload.displayAlias(),
				payload.configSnapshot(),
				payload.expectedCoreRevision(),
				payload.expectedSourceRevision()
			),
			buffer -> {
				RepeaterNetworkPayloadSupport.DecodedSavePayload decoded = RepeaterNetworkPayloadSupport.decodeSavePayload(buffer);
				return new SaveRepeaterPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.serial(),
					decoded.displayAlias(),
					decoded.configSnapshot(),
					decoded.expectedCoreRevision(),
					decoded.expectedSourceRevision()
				);
			}
		);

		public SaveRepeaterPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			serial = Math.max(0L, serial);
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configSnapshot = configSnapshot == null ? RepeaterConfigSnapshot.empty() : configSnapshot;
			expectedCoreRevision = Math.max(0L, expectedCoreRevision);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求打开转发器一侧配对界面的 C2S 请求。
	 */
	public record OpenRepeaterPairingPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String sideToken
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenRepeaterPairingPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_repeater_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenRepeaterPairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> RepeaterNetworkPayloadSupport.encodeOpenPairingPayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.serial(),
				payload.sideToken()
			),
			buffer -> {
				RepeaterNetworkPayloadSupport.DecodedOpenPairingPayload decoded = RepeaterNetworkPayloadSupport.decodeOpenPairingPayload(
					buffer
				);
				return new OpenRepeaterPairingPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.serial(),
					decoded.sideToken()
				);
			}
		);

		public OpenRepeaterPairingPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			serial = Math.max(0L, serial);
			sideToken = normalizePairingSide(sideToken);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 统一本地消息反馈回执。
	 */
	public record RepeaterFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RepeaterFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "repeater_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, RepeaterFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> RepeaterNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			buffer -> {
				RepeaterNetworkPayloadSupport.DecodedFeedbackPayload decoded = RepeaterNetworkPayloadSupport.decodeFeedbackPayload(
					buffer
				);
				return new RepeaterFeedbackPayload(decoded.success(), decoded.messageKey(), decoded.messageArgs());
			}
		);

		public RepeaterFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
