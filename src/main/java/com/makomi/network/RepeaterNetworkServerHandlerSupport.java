package com.makomi.network;

import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.RepeaterAliasMirrorSupport;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import com.makomi.data.RepeaterGraphSnapshotSupport;
import com.makomi.data.RepeaterItemData;
import com.makomi.item.RepeaterBlockItem;
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
 * 转发器网络服务端处理壳。
 */
final class RepeaterNetworkServerHandlerSupport {
	private RepeaterNetworkServerHandlerSupport() {
	}

	static void handleSaveRepeater(ServerPlayer player, RepeaterNetwork.SaveRepeaterPayload payload) {
		if (player == null || payload == null) {
			return;
		}

		TargetContext context = resolveTargetContext(player, payload.targetKind(), payload.dimensionKey(), payload.blockPosLong(), payload.selectedSlot(), payload.serial());
		if (context == null) {
			sendFeedback(player, false, "message.redstonelink.repeater.target_missing");
			return;
		}
		if (!validateRepeaterSerial(context.level(), context.serial())) {
			sendFeedback(player, false, "message.redstonelink.repeater.serial_invalid", Long.toString(context.serial()));
			return;
		}

		boolean blockTarget = payload.targetKind().usesBlockEntityTarget();
		boolean canEditPlacedBlock = com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.command().permissionLevel());
		if (blockTarget && !canEditPlacedBlock) {
			sendFeedback(player, false, "message.redstonelink.permission.insufficient");
			return;
		}

		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(payload.displayAlias());
		String currentAlias = RepeaterGraphSnapshotSupport.resolveAlias(context.level(), context.serial(), context.currentDisplayAlias());
		boolean aliasChanged = !normalizedAlias.equals(currentAlias);
		if (aliasChanged && !com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.command().otherPermissionLevel())) {
			sendFeedback(player, false, "message.redstonelink.permission.insufficient");
			return;
		}
		if (!normalizedAlias.isEmpty()) {
			NodeAliasSavedData.ValidationResult validation = NodeAliasSavedData.validateAlias(normalizedAlias);
			if (!validation.valid()) {
				LinkSetExecutionService.OperationFeedback feedback = PairingNetworkServerHandlerSupport.buildAliasValidationFeedback(
					normalizedAlias,
					validation
				);
				sendFeedback(player, feedback.success(), feedback.messageKey(), feedback.messageArgs().toArray(String[]::new));
				return;
			}
		}
		if (
			aliasChanged &&
			!CommandRateLimitService.tryAcquire(
				player.createCommandSourceStack(),
				CommandRateLimitService.CommandGroup.OTHER,
				1
			)
		) {
			sendFeedback(player, false, "message.redstonelink.command.rate_limit.exceeded");
			return;
		}

		LinkSetExecutionService.OperationFeedback aliasFailure = applyAliasChange(
			context.level(),
			context.serial(),
			currentAlias,
			normalizedAlias
		);
		if (aliasFailure != null) {
			sendFeedback(player, aliasFailure.success(), aliasFailure.messageKey(), aliasFailure.messageArgs().toArray(String[]::new));
			return;
		}

		RepeaterDelay requestedDelay = payload.configSnapshot() == null ? RepeaterDelay.ONE_TICK : payload.configSnapshot().delay();
		RepeaterConfigSnapshot graphSnapshot = RepeaterGraphSnapshotSupport.resolve(
			context.level(),
			context.serial(),
			new RepeaterConfigSnapshot("", "", requestedDelay)
		);
		if (context.blockEntity() != null) {
			context.blockEntity().applyEditorState(graphSnapshot);
		}
		if (!context.heldStack().isEmpty()) {
			RepeaterItemData.write(context.heldStack(), graphSnapshot);
			RepeaterGraphSnapshotSupport.syncItemSnapshot(context.heldStack(), context.level());
			player.containerMenu.broadcastChanges();
		}

		sendFeedback(player, true, "message.redstonelink.repeater.saved");
	}

	static void handleOpenRepeaterPairing(ServerPlayer player, RepeaterNetwork.OpenRepeaterPairingPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		TargetContext context = resolveTargetContext(player, payload.targetKind(), payload.dimensionKey(), payload.blockPosLong(), payload.selectedSlot(), payload.serial());
		if (context == null) {
			sendFeedback(player, false, "message.redstonelink.repeater.target_missing");
			return;
		}
		if (!validateRepeaterSerial(context.level(), context.serial())) {
			sendFeedback(player, false, "message.redstonelink.repeater.serial_invalid", Long.toString(context.serial()));
			return;
		}

		if (RepeaterNetwork.OUTPUT_SIDE_TOKEN.equals(payload.sideToken())) {
			PairingNetwork.openTriggerSourcePairing(player, context.serial(), LinkGuiDisplayContext.LINK_REPEATER);
			return;
		}
		PairingNetwork.openCorePairing(player, context.serial(), LinkGuiDisplayContext.LINK_REPEATER);
	}

	private static LinkSetExecutionService.OperationFeedback applyAliasChange(
		ServerLevel level,
		long serial,
		String currentAlias,
		String normalizedAlias
	) {
		if (level == null || serial <= 0L || normalizedAlias.equals(currentAlias)) {
			return null;
		}
		if (normalizedAlias.isEmpty()) {
			NodeAliasSavedData.RemoveResult removeResult = RepeaterAliasMirrorSupport.remove(level, LinkNodeType.CORE, serial);
			if (removeResult.removed()) {
				RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(level, LinkNodeType.CORE, serial);
			}
			return null;
		}
		NodeAliasSavedData.UpsertResult result = RepeaterAliasMirrorSupport.upsert(level, LinkNodeType.CORE, serial, normalizedAlias);
		if (!result.valid()) {
			return PairingNetworkServerHandlerSupport.buildAliasValidationFeedback(normalizedAlias, result.validation());
		}
		if (result.conflict()) {
			return PairingNetworkServerHandlerSupport.buildAliasConflictFeedback(LinkNodeType.CORE, result);
		}
		if (result.changed()) {
			RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(level, LinkNodeType.CORE, serial);
		}
		return null;
	}

	private static boolean validateRepeaterSerial(ServerLevel level, long serial) {
		if (level == null || serial <= 0L) {
			return false;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		return savedData.isRepeaterSerial(serial) &&
		savedData.isSerialAllocated(LinkNodeType.CORE, serial) &&
		savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, serial) &&
		!savedData.isSerialRetired(LinkNodeType.CORE, serial) &&
		!savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, serial);
	}

	private static TargetContext resolveTargetContext(
		ServerPlayer player,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial
	) {
		if (player == null || targetKind == null) {
			return null;
		}
		if (targetKind.usesBlockEntityTarget()) {
			LinkRepeaterBlockEntity blockEntity = resolveBlockEntity(player, dimensionKey, blockPosLong);
			if (blockEntity == null || blockEntity.getSerial() != serial) {
				return null;
			}
			return new TargetContext((ServerLevel) blockEntity.getLevel(), serial, blockEntity, ItemStack.EMPTY, "");
		}
		ItemStack heldStack = resolveHeldStack(player, selectedSlot);
		if (heldStack.isEmpty()) {
			return null;
		}
		long actualSerial = RepeaterItemData.ensureSerial(heldStack, player.level());
		if (actualSerial != serial) {
			return null;
		}
		return new TargetContext(
			player.level(),
			actualSerial,
			null,
			heldStack,
			com.makomi.data.LinkItemData.getDisplayAlias(heldStack)
		);
	}

	private static LinkRepeaterBlockEntity resolveBlockEntity(ServerPlayer player, String dimensionKey, long blockPosLong) {
		if (player == null || dimensionKey == null || dimensionKey.isBlank()) {
			return null;
		}
		Identifier dimensionLocation = Identifier.tryParse(dimensionKey);
		if (dimensionLocation == null) {
			return null;
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
		ServerLevel level = player.level().getServer().getLevel(dimension);
		if (level == null) {
			return null;
		}
		if (!(level.getBlockEntity(BlockPos.of(blockPosLong)) instanceof LinkRepeaterBlockEntity blockEntity)) {
			return null;
		}
		return blockEntity;
	}

	private static ItemStack resolveHeldStack(ServerPlayer player, int selectedSlot) {
		if (player == null || player.getInventory().getSelectedSlot() != selectedSlot) {
			return ItemStack.EMPTY;
		}
		ItemStack heldStack = player.getMainHandItem();
		if (heldStack.isEmpty() || !(heldStack.getItem() instanceof RepeaterBlockItem)) {
			return ItemStack.EMPTY;
		}
		return heldStack;
	}

	private static void sendFeedback(ServerPlayer player, boolean success, String messageKey, String... messageArgs) {
		if (player == null || messageKey == null || messageKey.isBlank()) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new RepeaterNetwork.RepeaterFeedbackPayload(success, messageKey, List.of(messageArgs))
		);
	}

	private record TargetContext(
		ServerLevel level,
		long serial,
		LinkRepeaterBlockEntity blockEntity,
		ItemStack heldStack,
		String currentDisplayAlias
	) {
	}
}
