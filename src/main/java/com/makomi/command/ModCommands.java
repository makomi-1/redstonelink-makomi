package com.makomi.command;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.item.PairableBlockItem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ModCommands {
	private ModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands
				.literal("redstonelink")
				.then(
					Commands
						.literal("pair")
						.then(
							Commands.literal("main").then(
								Commands.argument("serial", LongArgumentType.longArg(0L)).executes(
									context -> executePairByHand(context, InteractionHand.MAIN_HAND)
								)
							)
						)
						.then(
							Commands.literal("off").then(
								Commands.argument("serial", LongArgumentType.longArg(0L)).executes(
									context -> executePairByHand(context, InteractionHand.OFF_HAND)
								)
							)
						)
				)
				.then(
					Commands
						.literal("pair_node")
						.then(
							Commands.literal("button").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L)).then(
									Commands.argument("target_serial", LongArgumentType.longArg(0L)).executes(
										context -> executePairByNode(context, LinkNodeType.BUTTON)
									)
								)
							)
						)
				)
				.then(
					Commands
						.literal("set_links")
						.then(
							Commands.literal("button").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L))
									.executes(context -> executeSetLinks(context, LinkNodeType.BUTTON, false))
									.then(
										Commands.argument("targets", StringArgumentType.greedyString()).executes(
											context -> executeSetLinks(context, LinkNodeType.BUTTON, true)
										)
									)
							)
						)
						.then(
							Commands.literal("core").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L))
									.executes(context -> executeSetLinks(context, LinkNodeType.CORE, false))
									.then(
										Commands.argument("targets", StringArgumentType.greedyString()).executes(
											context -> executeSetLinks(context, LinkNodeType.CORE, true)
										)
									)
							)
						)
				)
				.then(Commands.literal("audit").executes(ModCommands::executeAudit))
				.then(
					Commands
						.literal("retire")
						.then(
							Commands.literal("core").then(
								Commands.argument("serial", LongArgumentType.longArg(1L))
									.executes(context -> executeRetireRequireConfirm(context, LinkNodeType.CORE))
									.then(
										Commands
											.literal("confirm")
											.executes(context -> executeRetire(context, LinkNodeType.CORE))
									)
							)
						)
						.then(
							Commands.literal("button").then(
								Commands.argument("serial", LongArgumentType.longArg(1L))
									.executes(context -> executeRetireRequireConfirm(context, LinkNodeType.BUTTON))
									.then(
										Commands
											.literal("confirm")
											.executes(context -> executeRetire(context, LinkNodeType.BUTTON))
									)
							)
						)
				)
		));
	}

	private static int executePairByHand(CommandContext<CommandSourceStack> context, InteractionHand hand) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		ItemStack heldStack = player.getItemInHand(hand);
		if (!(heldStack.getItem() instanceof PairableBlockItem pairableBlockItem)) {
			source.sendFailure(Component.translatable("message.redstonelink.not_pairable"));
			return 0;
		}

		LinkNodeType selfType = pairableBlockItem.getNodeType();
		if (selfType != LinkNodeType.BUTTON) {
			source.sendFailure(Component.translatable("message.redstonelink.button_source_only"));
			return 0;
		}

		long selfSerial = LinkItemData.ensureSerial(heldStack, player.serverLevel(), selfType);
		long targetSerial = LongArgumentType.getLong(context, "serial");

		int result = executeLinkUpdate(source, player, selfType, selfSerial, targetSerial, false, false);
		if (result == Command.SINGLE_SUCCESS) {
			LinkItemData.setPairSerial(heldStack, targetSerial);
		}
		return result;
	}

	private static int executePairByNode(CommandContext<CommandSourceStack> context, LinkNodeType sourceType) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}
		if (sourceType != LinkNodeType.BUTTON) {
			source.sendFailure(Component.translatable("message.redstonelink.button_source_only"));
			return 0;
		}

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		long targetSerial = LongArgumentType.getLong(context, "target_serial");
		int result = executeLinkUpdate(source, player, sourceType, sourceSerial, targetSerial, true, true);
		if (result == Command.SINGLE_SUCCESS) {
			updateNodeLastTargetSerial(player.serverLevel(), sourceType, sourceSerial, targetSerial);
		}
		return result;
	}

	private static int executeLinkUpdate(
		CommandSourceStack source,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial,
		boolean requireSourceExists,
		boolean requireTargetExists
	) {
		LinkSavedData savedData = LinkSavedData.get(player.serverLevel());
		if (!validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		if (requireSourceExists && savedData.findNode(sourceType, sourceSerial).isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.source_not_found", sourceSerial));
			return 0;
		}

		if (targetSerial <= 0L) {
			int removed = savedData.clearLinksForNode(sourceType, sourceSerial);
			syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.links_cleared", removed),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		LinkNodeType targetType = sourceType == LinkNodeType.BUTTON ? LinkNodeType.CORE : LinkNodeType.BUTTON;
		if (!validateTargetSerialActive(source, savedData, targetType, targetSerial)) {
			return 0;
		}
		if (requireTargetExists && savedData.findNode(targetType, targetSerial).isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.target_not_found", targetSerial));
			return 0;
		}

		boolean linkedNow;
		if (sourceType == LinkNodeType.BUTTON) {
			linkedNow = savedData.toggleLink(sourceSerial, targetSerial);
		} else {
			linkedNow = savedData.toggleLink(targetSerial, sourceSerial);
		}

		if (linkedNow) {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_added"), false);
		} else {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_removed"), false);
		}
		syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
		return Command.SINGLE_SUCCESS;
	}

	private static int executeAudit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		LinkSavedData.AuditSnapshot snapshot = savedData.createAuditSnapshot();
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.summary",
				snapshot.onlineCoreNodes(),
				snapshot.onlineButtonNodes(),
				snapshot.totalLinks(),
				snapshot.linksWithMissingEndpoint()
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.linked_serials",
				snapshot.linkedButtonSerialCount(),
				snapshot.linkedCoreSerialCount()
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.core_active_serials",
				formatSerialSet(savedData.getActiveSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.core_retired_serials",
				formatSerialSet(savedData.getRetiredSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_active_serials",
				formatSerialSet(savedData.getActiveSerials(LinkNodeType.BUTTON))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_retired_serials",
				formatSerialSet(savedData.getRetiredSerials(LinkNodeType.BUTTON))
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int executeRetireRequireConfirm(
		CommandContext<CommandSourceStack> context,
		LinkNodeType type
	) {
		CommandSourceStack source = context.getSource();
		long serial = LongArgumentType.getLong(context, "serial");
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.retire.confirm",
				typeCommandName(type),
				serial
			)
		);
		return 0;
	}

	private static int executeRetire(CommandContext<CommandSourceStack> context, LinkNodeType type) {
		CommandSourceStack source = context.getSource();
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData.RetireResult result = LinkSavedData.get(source.getLevel()).retireNode(type, serial);

		if (!result.nodeRemoved() && result.linksRemoved() == 0 && !result.retiredMarked()) {
			source.sendFailure(Component.translatable("message.redstonelink.retire.no_change", typeCommandName(type), serial));
			return 0;
		}

		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.retire.done",
				typeCommandName(type),
				serial,
				Boolean.toString(result.nodeRemoved()),
				result.linksRemoved()
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static String typeCommandName(LinkNodeType type) {
		return type == LinkNodeType.BUTTON ? "button" : "core";
	}

	private static int executeSetLinks(
		CommandContext<CommandSourceStack> context,
		LinkNodeType sourceType,
		boolean hasTargets
	) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		LinkSavedData savedData = LinkSavedData.get(player.serverLevel());
		if (!validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		Set<Long> targets;
		if (!hasTargets) {
			targets = Set.of();
		} else {
			String raw = StringArgumentType.getString(context, "targets");
			TargetParseResult parseResult = parseTargetSerials(raw);
			if (!parseResult.invalidEntries().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.invalid_target_tokens",
						String.join(", ", parseResult.invalidEntries())
					)
				);
				return 0;
			}
			targets = parseResult.targets();
		}

		LinkNodeType targetType = sourceType == LinkNodeType.BUTTON ? LinkNodeType.CORE : LinkNodeType.BUTTON;
		List<Long> unallocatedTargets = new ArrayList<>();
		List<Long> retiredTargets = new ArrayList<>();
		for (long targetSerial : targets) {
			if (!savedData.isSerialAllocated(targetType, targetSerial)) {
				unallocatedTargets.add(targetSerial);
				continue;
			}
			if (savedData.isSerialRetired(targetType, targetSerial)) {
				retiredTargets.add(targetSerial);
			}
		}
		if (!unallocatedTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_unallocated",
					formatSerialList(unallocatedTargets)
				)
			);
			return 0;
		}
		if (!retiredTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_retired",
					formatSerialList(retiredTargets)
				)
			);
			return 0;
		}

		savedData.clearLinksForNode(sourceType, sourceSerial);
		List<Long> offlineTargets = new ArrayList<>();
		int added = 0;
		long lastTarget = 0L;
		for (long targetSerial : targets) {
			if (savedData.findNode(targetType, targetSerial).isEmpty()) {
				offlineTargets.add(targetSerial);
			}

			boolean addedNow = sourceType == LinkNodeType.BUTTON
				? savedData.toggleLink(sourceSerial, targetSerial)
				: savedData.toggleLink(targetSerial, sourceSerial);
			if (addedNow) {
				added++;
				lastTarget = targetSerial;
			}
		}

		updateNodeLastTargetSerial(player.serverLevel(), sourceType, sourceSerial, lastTarget);
		syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
		final int addedCount = added;
		source.sendSuccess(() -> Component.translatable("message.redstonelink.set_links_done", addedCount), false);
		if (!offlineTargets.isEmpty()) {
			String offline = offlineTargets.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("-");
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.offline_targets_saved", offline),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static void syncPlayerItemLinkSnapshot(
		ServerPlayer player,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		Set<Long> linkedSerials = sourceType == LinkNodeType.BUTTON
			? savedData.getLinkedCores(sourceSerial)
			: savedData.getLinkedButtons(sourceSerial);

		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (LinkItemData.getSerial(stack) != sourceSerial) {
				continue;
			}
			if (LinkItemData.getNodeType(stack).orElse(null) != sourceType) {
				continue;
			}
			LinkItemData.setLinkedSerials(stack, linkedSerials);
		}
	}

	private static void updateNodeLastTargetSerial(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		savedData.findNode(sourceType, sourceSerial).ifPresent(node -> {
			ServerLevel nodeLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (nodeLevel == null || !nodeLevel.hasChunkAt(node.pos())) {
				return;
			}

			BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
				pairableNodeBlockEntity.setLastTargetSerial(targetSerial);
			}
		});
	}

	private static TargetParseResult parseTargetSerials(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return new TargetParseResult(Set.of(), List.of());
		}

		String[] tokens = rawText.trim().split("[,;\\s]+");
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		List<String> invalidEntries = new ArrayList<>();
		for (String token : tokens) {
			if (token.isBlank()) {
				continue;
			}
			if (!token.matches("[0-9]+")) {
				invalidEntries.add(token);
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(token);
			} catch (NumberFormatException ignored) {
				invalidEntries.add(token);
				continue;
			}
			if (serial <= 0L) {
				invalidEntries.add(token);
				continue;
			}
			result.add(serial);
		}
		return new TargetParseResult(Set.copyOf(result), List.copyOf(invalidEntries));
	}

	private static boolean validateSourceSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		if (sourceSerial <= 0L || !savedData.isSerialAllocated(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_unallocated", sourceSerial));
			return false;
		}
		if (savedData.isSerialRetired(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_retired", sourceSerial));
			return false;
		}
		return true;
	}

	private static boolean validateTargetSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType targetType,
		long targetSerial
	) {
		if (targetSerial <= 0L || !savedData.isSerialAllocated(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_unallocated", targetSerial));
			return false;
		}
		if (savedData.isSerialRetired(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_retired", targetSerial));
			return false;
		}
		return true;
	}

	private static String formatSerialList(List<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}

	private static String formatSerialSet(Set<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}

	private record TargetParseResult(Set<Long> targets, List<String> invalidEntries) {}
}
