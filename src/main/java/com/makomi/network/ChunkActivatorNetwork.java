package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.data.ChunkActivatorConfigStateSnapshot;
import com.makomi.data.ChunkActivatorItemData;
import com.makomi.data.NodeAliasDisplayUtil;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 区块激活器编辑器网络通道。
 */
public final class ChunkActivatorNetwork {
	private ChunkActivatorNetwork() {
	}

	/**
	 * 注册全部 payload 与接包器。
	 */
	public static void register() {
		ChunkActivatorNetworkRegistrationSupport.register();
	}

	/**
	 * 打开已放置区块激活器编辑器。
	 */
	public static void openEditor(ServerPlayer player, LinkChunkActivatorBlockEntity blockEntity) {
		if (player == null || blockEntity == null || blockEntity.getLevel() == null) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new OpenChunkActivatorEditorPayload(
				LinkFilterEditorTargetKind.BLOCK_ENTITY,
				blockEntity.getLevel().dimension().identifier().toString(),
				blockEntity.getBlockPos().asLong(),
				-1,
				blockEntity.displayAlias(),
				blockEntity.snapshot()
			)
		);
	}

	/**
	 * 打开主手手持区块激活器编辑器。
	 */
	public static void openHeldItemEditor(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new OpenChunkActivatorEditorPayload(
				LinkFilterEditorTargetKind.HELD_MAIN_HAND,
				"",
				0L,
				player.getInventory().selected,
				ChunkActivatorItemData.getDisplayAlias(stack),
				ChunkActivatorItemData.read(stack)
			)
		);
	}

	/**
	 * 服务端打开编辑器的 S2C 包。
	 */
	public record OpenChunkActivatorEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenChunkActivatorEditorPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_chunk_activator_editor")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenChunkActivatorEditorPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> ChunkActivatorNetworkPayloadSupport.encodeOpenEditorPayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.displayAlias(),
				payload.configStateSnapshot()
			),
			buffer -> {
				ChunkActivatorNetworkPayloadSupport.DecodedOpenEditorPayload decoded = ChunkActivatorNetworkPayloadSupport.decodeOpenEditorPayload(
					buffer
				);
				return new OpenChunkActivatorEditorPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.displayAlias(),
					decoded.configStateSnapshot()
				);
			}
		);

		public OpenChunkActivatorEditorPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configStateSnapshot = configStateSnapshot == null
				? new ChunkActivatorConfigStateSnapshot(null, null, null)
				: configStateSnapshot;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端提交区块激活器配置的 C2S 请求。
	 */
	public record SaveChunkActivatorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SaveChunkActivatorPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "save_chunk_activator")
		);
		public static final StreamCodec<FriendlyByteBuf, SaveChunkActivatorPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> ChunkActivatorNetworkPayloadSupport.encodeSavePayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.displayAlias(),
				payload.configStateSnapshot()
			),
			buffer -> {
				ChunkActivatorNetworkPayloadSupport.DecodedSavePayload decoded = ChunkActivatorNetworkPayloadSupport.decodeSavePayload(
					buffer
				);
				return new SaveChunkActivatorPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.displayAlias(),
					decoded.configStateSnapshot()
				);
			}
		);

		public SaveChunkActivatorPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configStateSnapshot = configStateSnapshot == null
				? new ChunkActivatorConfigStateSnapshot(null, null, null)
				: configStateSnapshot;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 统一区块激活器反馈回执。
	 */
	public record ChunkActivatorFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ChunkActivatorFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "chunk_activator_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, ChunkActivatorFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> ChunkActivatorNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			buffer -> {
				ChunkActivatorNetworkPayloadSupport.DecodedFeedbackPayload decoded = ChunkActivatorNetworkPayloadSupport.decodeFeedbackPayload(
					buffer
				);
				return new ChunkActivatorFeedbackPayload(decoded.success(), decoded.messageKey(), decoded.messageArgs());
			}
		);

		public ChunkActivatorFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
