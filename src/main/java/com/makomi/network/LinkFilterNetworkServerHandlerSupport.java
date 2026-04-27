package com.makomi.network;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterItemData;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterTargetMode;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.item.LinkFilterBlockItem;
import com.makomi.util.SerialParseUtil;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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
		String normalizedDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(payload.displayAlias());
		if (!normalizedDisplayAlias.isEmpty()) {
			NodeAliasSavedData.ValidationResult aliasValidation = NodeAliasSavedData.validateAlias(normalizedDisplayAlias);
			if (!aliasValidation.valid()) {
				LinkSetExecutionService.OperationFeedback feedback = PairingNetworkServerHandlerSupport.buildAliasValidationFeedback(
					normalizedDisplayAlias,
					aliasValidation
				);
				sendFeedback(player, false, feedback.messageKey(), feedback.messageArgs().toArray(String[]::new));
				return;
			}
		}
		LinkFilterConfigSnapshot configSnapshot = payload.configSnapshot();
		if (configSnapshot.usesSerialTarget() && configSnapshot.serialExpression().length() > RedstoneLinkConfig.command().linkSetMaxInputLength()) {
			sendFeedback(
				player,
				false,
				"message.redstonelink.link_filter.input_too_long",
				Integer.toString(RedstoneLinkConfig.command().linkSetMaxInputLength())
			);
			return;
		}

		SerialParseUtil.OrderedTargetParseResult parseResult = parseTargets(configSnapshot);
		if (configSnapshot.usesSerialTarget()) {
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
			NetworkActiveSerialValidationSupport.ValidationResult validationResult =
				NetworkActiveSerialValidationSupport.collectInvalidSerials(
					LinkSavedData.get(player.level()),
					payload.filterKind().servicedNodeType(),
					parseResult.orderedTargets()
				);
			if (validationResult.hasUnallocated()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.invalid_target_unallocated",
					String.join(", ", validationResult.unallocatedSerials())
				);
				return;
			}
			if (validationResult.hasRetired()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.invalid_target_retired",
					String.join(", ", validationResult.retiredSerials())
				);
				return;
			}
		}

		if (payload.targetKind().usesBlockEntityTarget()) {
			if (!com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.command().permissionLevel())) {
				sendFeedback(player, false, "message.redstonelink.permission.insufficient");
				return;
			}
			AbstractLinkFilterBlockEntity filterBlockEntity = resolveFilterBlockEntity(player, payload);
			if (filterBlockEntity == null) {
				sendFeedback(player, false, "message.redstonelink.link_filter.target_missing");
				return;
			}
			filterBlockEntity.applyEditorState(normalizedDisplayAlias, configSnapshot);
		} else {
			ItemStack heldFilterStack = resolveHeldFilterStack(player, payload);
			if (heldFilterStack.isEmpty()) {
				sendFeedback(player, false, "message.redstonelink.link_filter.target_missing");
				return;
			}
			LinkFilterItemData.write(heldFilterStack, configSnapshot);
			LinkFilterItemData.setDisplayAlias(heldFilterStack, normalizedDisplayAlias);
			LinkFilterItemData.syncNodeSetDisplayTexts(heldFilterStack, player.level(), payload.filterKind().servicedNodeType());
		}

		if (configSnapshot.nodeSetMode() == LinkFilterNodeSetMode.WHITELIST && isWhitelistTargetEmpty(configSnapshot, parseResult)) {
			sendFeedback(player, true, "message.redstonelink.link_filter.saved_whitelist_empty");
			return;
		}
		if (configSnapshot.usesSerialTarget() && !parseResult.duplicateEntries().isEmpty()) {
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
	 * 解析序号模式下的节点表达式；频道模式直接回退为空结果。
	 */
	private static SerialParseUtil.OrderedTargetParseResult parseTargets(LinkFilterConfigSnapshot configSnapshot) {
		if (configSnapshot == null || configSnapshot.targetMode() == LinkFilterTargetMode.CHANNEL) {
			return SerialParseUtil.parseTargetsOrdered("", 0);
		}
		return SerialParseUtil.parseTargetsOrdered(
			configSnapshot.serialExpression(),
			RedstoneLinkConfig.general().maxTargetsPerSetLinks()
		);
	}

	/**
	 * 判断白名单模式下当前目标是否为空。
	 */
	private static boolean isWhitelistTargetEmpty(
		LinkFilterConfigSnapshot configSnapshot,
		SerialParseUtil.OrderedTargetParseResult parseResult
	) {
		if (configSnapshot == null) {
			return true;
		}
		if (configSnapshot.usesChannelTarget()) {
			return configSnapshot.channel() <= 0L;
		}
		return parseResult == null || parseResult.orderedTargets().isEmpty();
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
		Optional<Identifier> dimensionLocation = Identifier.tryParse(payload.dimensionKey()) == null
			? Optional.empty()
			: Optional.of(Identifier.parse(payload.dimensionKey()));
		if (dimensionLocation.isEmpty()) {
			return null;
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation.get());
		ServerLevel level = player.level().getServer().getLevel(dimension);
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
		if (player.getInventory().getSelectedSlot() != payload.selectedSlot()) {
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
