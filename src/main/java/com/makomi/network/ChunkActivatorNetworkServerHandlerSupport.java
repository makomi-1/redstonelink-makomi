package com.makomi.network;

import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorConfigStateSnapshot;
import com.makomi.data.ChunkActivatorItemData;
import com.makomi.data.CrossChunkEffectiveWhitelistService;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.PlacedChunkActivatorSavedData;
import com.makomi.item.ChunkActivatorBlockItem;
import com.makomi.util.SerialParseUtil;
import java.util.List;
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
 * 区块激活器网络服务端处理壳。
 */
final class ChunkActivatorNetworkServerHandlerSupport {
	private ChunkActivatorNetworkServerHandlerSupport() {
	}

	static void handleSaveChunkActivator(ServerPlayer player, ChunkActivatorNetwork.SaveChunkActivatorPayload payload) {
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

		ChunkActivatorConfigStateSnapshot configStateSnapshot = payload.configStateSnapshot();
		SerialParseUtil.OrderedTargetParseResult activeParseResult = validateConfigState(player, configStateSnapshot);
		if (activeParseResult == null) {
			return;
		}

		if (payload.targetKind().usesBlockEntityTarget()) {
			if (!com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.command().permissionLevel())) {
				sendFeedback(player, false, "message.redstonelink.permission.insufficient");
				return;
			}
			LinkChunkActivatorBlockEntity blockEntity = resolveBlockEntity(player, payload);
			if (blockEntity == null) {
				sendFeedback(player, false, "message.redstonelink.chunk_activator.target_missing");
				return;
			}
			if (!ensureResidentCapacityForPlacedActivator(player, blockEntity, configStateSnapshot)) {
				return;
			}
			blockEntity.applyEditorState(normalizedDisplayAlias, configStateSnapshot);
		} else {
			ItemStack heldStack = resolveHeldStack(player, payload);
			if (heldStack.isEmpty()) {
				sendFeedback(player, false, "message.redstonelink.chunk_activator.target_missing");
				return;
			}
			ChunkActivatorItemData.write(heldStack, configStateSnapshot);
			ChunkActivatorItemData.setDisplayAlias(heldStack, normalizedDisplayAlias);
			ChunkActivatorItemData.syncNodeSetDisplayTexts(heldStack, player.level());
		}

		if (!activeParseResult.duplicateEntries().isEmpty()) {
			sendFeedback(
				player,
				true,
				"message.redstonelink.duplicate_targets_deduped",
				CommandTreeSupport.formatSerialCollection(activeParseResult.duplicateEntries())
			);
			return;
		}
		sendFeedback(player, true, "message.redstonelink.chunk_activator.saved");
	}

	private static SerialParseUtil.OrderedTargetParseResult validateConfigState(
		ServerPlayer player,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
		LinkSavedData savedData = LinkSavedData.get(player.level());
		ChunkActivatorConfigStateSnapshot normalized = configStateSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(null, null, null)
			: configStateSnapshot;
		SerialParseUtil.OrderedTargetParseResult activeParseResult = null;
		for (LinkNodeType type : new LinkNodeType[] { LinkNodeType.TRIGGER_SOURCE, LinkNodeType.CORE }) {
			ChunkActivatorConfigSnapshot configSnapshot = normalized.configFor(type);
			if (configSnapshot.serialExpression().length() > RedstoneLinkConfig.command().linkSetMaxInputLength()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.chunk_activator.input_too_long",
					Integer.toString(RedstoneLinkConfig.command().linkSetMaxInputLength())
				);
				return null;
			}

			SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
				configSnapshot.serialExpression(),
				PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
			);
			if (!parseResult.invalidEntries().isEmpty()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.pairing.invalid_tokens",
					String.join(", ", parseResult.invalidEntries())
				);
				return null;
			}
			if (parseResult.exceedLimit()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.chunk_activator.too_many_serials",
					Integer.toString(PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE)
				);
				return null;
			}
			NetworkActiveSerialValidationSupport.ValidationResult validationResult =
				NetworkActiveSerialValidationSupport.collectInvalidSerials(savedData, type, parseResult.orderedTargets());
			if (validationResult.hasUnallocated()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.invalid_target_unallocated",
					String.join(", ", validationResult.unallocatedSerials())
				);
				return null;
			}
			if (validationResult.hasRetired()) {
				sendFeedback(
					player,
					false,
					"message.redstonelink.invalid_target_retired",
					String.join(", ", validationResult.retiredSerials())
				);
				return null;
			}
			if (type == normalized.activeType()) {
				activeParseResult = parseResult;
			}
		}
		return activeParseResult == null
			? SerialParseUtil.parseTargetsOrdered("", PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE)
			: activeParseResult;
	}

	/**
	 * 校验已放置区块激活器保存后是否会超过 resident 总上限。
	 */
	private static boolean ensureResidentCapacityForPlacedActivator(
		ServerPlayer player,
		LinkChunkActivatorBlockEntity blockEntity,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
		if (player == null || blockEntity == null || !(blockEntity.getLevel() instanceof ServerLevel level)) {
			return false;
		}
		int effectiveResidents = CrossChunkEffectiveWhitelistService.countDistinctResidentsAfterActivatorChange(
			level,
			level.dimension(),
			blockEntity.getBlockPos(),
			configStateSnapshot,
			blockEntity.active()
		);
		int residentLimit = RedstoneLinkConfig.crossChunk().residentMaxEntries();
		if (effectiveResidents <= residentLimit) {
			return true;
		}
		sendFeedback(
			player,
			false,
			"message.redstonelink.chunk_activator.resident.limit_exceeded",
			Integer.toString(effectiveResidents),
			Integer.toString(residentLimit)
		);
		return false;
	}

	private static LinkChunkActivatorBlockEntity resolveBlockEntity(
		ServerPlayer player,
		ChunkActivatorNetwork.SaveChunkActivatorPayload payload
	) {
		if (player == null || payload == null || payload.dimensionKey().isBlank()) {
			return null;
		}
		Identifier dimensionLocation = Identifier.tryParse(payload.dimensionKey());
		if (dimensionLocation == null) {
			return null;
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
		ServerLevel level = player.level().getServer().getLevel(dimension);
		if (level == null) {
			return null;
		}
		if (!(level.getBlockEntity(BlockPos.of(payload.blockPosLong())) instanceof LinkChunkActivatorBlockEntity blockEntity)) {
			return null;
		}
		return blockEntity;
	}

	private static ItemStack resolveHeldStack(ServerPlayer player, ChunkActivatorNetwork.SaveChunkActivatorPayload payload) {
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
		return heldStack.getItem() instanceof ChunkActivatorBlockItem ? heldStack : ItemStack.EMPTY;
	}

	private static void sendFeedback(ServerPlayer player, boolean success, String messageKey, String... messageArgs) {
		if (player == null || messageKey == null || messageKey.isBlank()) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new ChunkActivatorNetwork.ChunkActivatorFeedbackPayload(success, messageKey, List.of(messageArgs))
		);
	}
}
