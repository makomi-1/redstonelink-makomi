package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterItemData;
import com.makomi.data.LinkFilterKind;
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
 * 过滤器编辑器网络通道。
 */
public final class LinkFilterNetwork {
	private LinkFilterNetwork() {
	}

	/**
	 * 注册全部 payload 与接包器。
	 */
	public static void register() {
		LinkFilterNetworkRegistrationSupport.register();
	}

	/**
	 * 打开过滤器编辑器。
	 */
	public static void openEditor(ServerPlayer player, AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (player == null || filterBlockEntity == null || filterBlockEntity.getLevel() == null) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new OpenFilterEditorPayload(
				LinkFilterEditorTargetKind.BLOCK_ENTITY,
				filterBlockEntity.getLevel().dimension().identifier().toString(),
				filterBlockEntity.getBlockPos().asLong(),
				-1,
				filterBlockEntity.filterKind(),
				filterBlockEntity.displayAlias(),
				filterBlockEntity.snapshot()
			)
		);
	}

	/**
	 * 打开主手手持过滤器编辑器。
	 */
	public static void openHeldItemEditor(ServerPlayer player, ItemStack stack, LinkFilterKind filterKind) {
		if (player == null || stack == null || stack.isEmpty() || filterKind == null) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new OpenFilterEditorPayload(
				LinkFilterEditorTargetKind.HELD_MAIN_HAND,
				"",
				0L,
				player.getInventory().getSelectedSlot(),
				filterKind,
				LinkFilterItemData.getDisplayAlias(stack),
				LinkFilterItemData.read(stack)
			)
		);
	}

	/**
	 * 服务端打开编辑器的 S2C 包。
	 */
	public record OpenFilterEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenFilterEditorPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_link_filter_editor")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenFilterEditorPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> LinkFilterNetworkPayloadSupport.encodeOpenEditorPayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.filterKind(),
				payload.displayAlias(),
				payload.configSnapshot()
			),
			buffer -> {
				LinkFilterNetworkPayloadSupport.DecodedOpenEditorPayload decoded = LinkFilterNetworkPayloadSupport.decodeOpenEditorPayload(
					buffer
				);
				return new OpenFilterEditorPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.filterKind(),
					decoded.displayAlias(),
					decoded.configSnapshot()
				);
			}
		);

		public OpenFilterEditorPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			filterKind = filterKind == null ? LinkFilterKind.SEND : filterKind;
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configSnapshot = configSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : configSnapshot;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端提交过滤器配置的 C2S 请求。
	 */
	public record SaveFilterPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SaveFilterPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "save_link_filter")
		);
		public static final StreamCodec<FriendlyByteBuf, SaveFilterPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> LinkFilterNetworkPayloadSupport.encodeSavePayload(
				buffer,
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.filterKind(),
				payload.displayAlias(),
				payload.configSnapshot()
			),
			buffer -> {
				LinkFilterNetworkPayloadSupport.DecodedSavePayload decoded = LinkFilterNetworkPayloadSupport.decodeSavePayload(buffer);
				return new SaveFilterPayload(
					decoded.targetKind(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.selectedSlot(),
					decoded.filterKind(),
					decoded.displayAlias(),
					decoded.configSnapshot()
				);
			}
		);

		public SaveFilterPayload {
			targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			selectedSlot = targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
			filterKind = filterKind == null ? LinkFilterKind.SEND : filterKind;
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
			configSnapshot = configSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : configSnapshot;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 统一过滤器反馈回执。
	 */
	public record FilterFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<FilterFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "link_filter_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, FilterFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> LinkFilterNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			buffer -> {
				LinkFilterNetworkPayloadSupport.DecodedFeedbackPayload decoded = LinkFilterNetworkPayloadSupport.decodeFeedbackPayload(
					buffer
				);
				return new FilterFeedbackPayload(decoded.success(), decoded.messageKey(), decoded.messageArgs());
			}
		);

		public FilterFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
