package com.makomi.command;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.item.PairableItem;
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

/**
 * RedstoneLink 服务端命令入口。
 * <p>
 * 提供手持/节点配对、批量覆盖链接、审计信息与节点退役等运维能力。
 * </p>
 */
public final class ModCommands {
	private ModCommands() {
	}

	/**
	 * 注册 /redstonelink 命令树。
	 */
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

	/**
	 * 使用手持可配对物执行单目标切换配对。
	 */
	private static int executePairByHand(CommandContext<CommandSourceStack> context, InteractionHand hand) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		ItemStack heldStack = player.getItemInHand(hand);
		if (!(heldStack.getItem() instanceof PairableItem pairableItem)) {
			source.sendFailure(Component.translatable("message.redstonelink.not_pairable"));
			return 0;
		}

		LinkNodeType selfType = pairableItem.getNodeType();
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

	/**
	 * 以节点序列号为源执行单目标切换配对。
	 */
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

	/**
	 * 单目标链接更新核心流程（添加/移除/清空）。
	 */
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
		boolean targetOffline = savedData.findNode(targetType, targetSerial).isEmpty();
		if (requireTargetExists && targetOffline) {
			source.sendFailure(Component.translatable("message.redstonelink.target_not_found", targetSerial));
			return 0;
		}
		if (!requireTargetExists && targetOffline && !RedstoneLinkConfig.allowOfflineTargetBinding()) {
			source.sendFailure(Component.translatable("message.redstonelink.offline_targets_blocked", Long.toString(targetSerial)));
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

	/**
	 * 输出当前链路审计信息到命令反馈。
	 */
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

	/**
	 * retire 命令第一阶段：仅提示确认，不执行退役。
	 */
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

	/**
	 * retire confirm 实际执行入口。
	 */
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

	/**
	 * 批量覆盖设置链接集合。
	 */
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
		int maxTargets = RedstoneLinkConfig.maxTargetsPerSetLinks();
		if (targets.size() > maxTargets) {
			source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
			return 0;
		}

		// 分阶段校验目标：未分配、已退役、离线，分别给出可定位的失败提示。
		LinkNodeType targetType = sourceType == LinkNodeType.BUTTON ? LinkNodeType.CORE : LinkNodeType.BUTTON;
		List<Long> unallocatedTargets = new ArrayList<>();
		List<Long> retiredTargets = new ArrayList<>();
		List<Long> offlineTargets = new ArrayList<>();
		for (long targetSerial : targets) {
			if (!savedData.isSerialAllocated(targetType, targetSerial)) {
				unallocatedTargets.add(targetSerial);
				continue;
			}
			if (savedData.isSerialRetired(targetType, targetSerial)) {
				retiredTargets.add(targetSerial);
				continue;
			}
			if (savedData.findNode(targetType, targetSerial).isEmpty()) {
				offlineTargets.add(targetSerial);
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
		boolean allowOfflineBinding = RedstoneLinkConfig.allowOfflineTargetBinding();
		if (!allowOfflineBinding && !offlineTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.offline_targets_blocked",
					formatSerialList(offlineTargets)
				)
			);
			return 0;
		}

		// set_links 语义为“覆盖集合”，因此先清空旧关系再写入新关系。
		savedData.clearLinksForNode(sourceType, sourceSerial);
		int added = 0;
		long lastTarget = 0L;
		for (long targetSerial : targets) {
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
		if (allowOfflineBinding && !offlineTargets.isEmpty()) {
			String offline = offlineTargets.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("-");
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.offline_targets_saved", offline),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 同步玩家背包中同序列号物品的链接快照。
	 */
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

	/**
	 * 回写在线节点方块实体的最近目标序列号。
	 */
	private static void updateNodeLastTargetSerial(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		savedData.findNode(sourceType, sourceSerial).ifPresent(node -> {
			ServerLevel nodeLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
				return;
			}

			BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
				pairableNodeBlockEntity.setLastTargetSerial(targetSerial);
			}
		});
	}

	/**
	 * 解析序列号列表文本（逗号/分号/空白分隔）。
	 */
	private static TargetParseResult parseTargetSerials(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return new TargetParseResult(Set.of(), List.of());
		}

		// 支持逗号、分号和空白混输，便于命令行快速粘贴序列号列表。
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

	/**
	 * 校验源序列号处于已分配且未退役状态。
	 */
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

	/**
	 * 校验目标序列号处于已分配且未退役状态。
	 */
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

	/**
	 * 以稳定升序格式化序列号列表。
	 */
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

	/**
	 * 以稳定升序格式化序列号集合。
	 */
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
