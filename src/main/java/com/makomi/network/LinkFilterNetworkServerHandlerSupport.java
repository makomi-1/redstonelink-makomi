package com.makomi.network;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterItemData;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.item.LinkFilterBlockItem;
import com.makomi.util.SerialParseUtil;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 过滤器网络服务端处理壳。
 */
final class LinkFilterNetworkServerHandlerSupport {
	private LinkFilterNetworkServerHandlerSupport() {
	}

	/**
	 * 处理过滤器保存请求。
	 */
	static void handleSaveFilter(ServerPlayer player, LinkFilterNetwork.SaveFilterPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		LinkFilterConfigSnapshot configSnapshot = payload.configSnapshot();
		if (configSnapshot.serialExpression().length() > RedstoneLinkConfig.command().linkSetMaxInputLength()) {
			sendFeedback(
				player,
				false,
				"message.redstonelink.link_filter.input_too_long",
				Integer.toString(RedstoneLinkConfig.command().linkSetMaxInputLength())
			);
			return;
		}

		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			configSnapshot.serialExpression(),
			RedstoneLinkConfig.general().maxTargetsPerSetLinks()
		);
		if (!parseResult.invalidEntries().isEmpty()) {
			sendFeedback(
				player,
				false,
				"message.redstonelink.pairing.invalid_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
			return;
		}
		if (parseResult.exceedLimit()) {
			sendFeedback(
				player,
				false,
				"message.redstonelink.link_filter.too_many_serials",
				Integer.toString(RedstoneLinkConfig.general().maxTargetsPerSetLinks())
			);
			return;
		}

		if (payload.targetKind().usesBlockEntityTarget()) {
			if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
				sendFeedback(player, false, "message.redstonelink.permission.insufficient");
				return;
			}
			AbstractLinkFilterBlockEntity filterBlockEntity = resolveFilterBlockEntity(player, payload);
			if (filterBlockEntity == null) {
				sendFeedback(player, false, "message.redstonelink.link_filter.target_missing");
				return;
			}
			filterBlockEntity.applySnapshot(configSnapshot);
		} else {
			ItemStack heldFilterStack = resolveHeldFilterStack(player, payload);
			if (heldFilterStack.isEmpty()) {
				sendFeedback(player, false, "message.redstonelink.link_filter.target_missing");
				return;
			}
			LinkFilterItemData.write(heldFilterStack, configSnapshot);
		}

		if (configSnapshot.nodeSetMode() == LinkFilterNodeSetMode.WHITELIST && parseResult.orderedTargets().isEmpty()) {
			sendFeedback(player, true, "message.redstonelink.link_filter.saved_whitelist_empty");
			return;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			sendFeedback(
				player,
				true,
				"message.redstonelink.duplicate_targets_deduped",
				CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
			);
		}
		sendFeedback(player, true, "message.redstonelink.link_filter.saved");
	}

	/**
	 * 解析并校验客户端要保存的过滤器方块实体。
	 */
	private static AbstractLinkFilterBlockEntity resolveFilterBlockEntity(
		ServerPlayer player,
		LinkFilterNetwork.SaveFilterPayload payload
	) {
		if (player == null || payload == null || payload.dimensionKey().isBlank()) {
			return null;
		}
		Optional<ResourceLocation> dimensionLocation = ResourceLocation.tryParse(payload.dimensionKey()) == null
			? Optional.empty()
			: Optional.of(ResourceLocation.parse(payload.dimensionKey()));
		if (dimensionLocation.isEmpty()) {
			return null;
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation.get());
		ServerLevel level = player.server.getLevel(dimension);
		if (level == null) {
			return null;
		}
		if (!(level.getBlockEntity(BlockPos.of(payload.blockPosLong())) instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
			return null;
		}
		return filterBlockEntity.filterKind() == payload.filterKind() ? filterBlockEntity : null;
	}

	/**
	 * 解析当前主手手持过滤器物品。
	 */
	private static ItemStack resolveHeldFilterStack(ServerPlayer player, LinkFilterNetwork.SaveFilterPayload payload) {
		if (player == null || payload == null || !payload.targetKind().usesHeldMainHandTarget()) {
			return ItemStack.EMPTY;
		}
		if (player.getInventory().selected != payload.selectedSlot()) {
			return ItemStack.EMPTY;
		}
		ItemStack heldStack = player.getMainHandItem();
		if (heldStack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		if (!(heldStack.getItem() instanceof LinkFilterBlockItem filterBlockItem)) {
			return ItemStack.EMPTY;
		}
		return filterBlockItem.filterKind() == payload.filterKind() ? heldStack : ItemStack.EMPTY;
	}

	/**
	 * 下发统一反馈回执。
	 */
	private static void sendFeedback(ServerPlayer player, boolean success, String messageKey, String... messageArgs) {
		if (player == null || messageKey == null || messageKey.isBlank()) {
			return;
		}
		ServerPlayNetworking.send(player, new LinkFilterNetwork.FilterFeedbackPayload(success, messageKey, List.of(messageArgs)));
	}
}
