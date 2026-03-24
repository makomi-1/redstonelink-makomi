package com.makomi.command.link;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.privacy.CurrentLinksPrivacyCommandRegistry;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.InternalDispatchDeltaEvents;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * `link` 命令注册器。
 * <p>
 * 负责链接增量写入、覆盖设置、读取，以及子命令树装配。
 * </p>
 */
public final class LinkCommandRegistry {
	private LinkCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink link` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("link")
			.then(
				Commands
					.literal("add")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("source_serial", LongArgumentType.longArg(1L)).then(
								Commands
									.argument("target_serial", LongArgumentType.longArg(1L))
									.executes(LinkCommandRegistry::executeLinkAddWithTypeArg)
							)
						)
					)
			)
			.then(
				Commands
					.literal("remove")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("source_serial", LongArgumentType.longArg(1L)).then(
								Commands
									.argument("target_serial", LongArgumentType.longArg(1L))
									.executes(LinkCommandRegistry::executeLinkRemoveWithTypeArg)
							)
						)
					)
			)
			.then(
				Commands
					.literal("set")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("source_serial", LongArgumentType.longArg(1L))
								.executes(context -> executeLinkSetWithTypeArg(context, false, false))
								.then(
									Commands.argument("targets", SerialBatchArgumentType.serialBatch())
										.executes(context -> executeLinkSetWithTypeArg(context, true, false))
										.then(
											Commands.literal("confirm")
												.executes(context -> executeLinkSetWithTypeArg(context, true, true))
										)
								)
						)
					)
			)
			.then(
				Commands
					.literal("get")
					.requires(CommandTreeSupport::hasOtherCommandPermission)
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands
								.argument("serial", LongArgumentType.longArg(1L))
								.executes(LinkCommandRegistry::executeLinkGet)
						)
					)
			)
			.then(CurrentLinksPrivacyCommandRegistry.createRoot())
			.then(LinkWriteControlCommandRegistry.createRoot());
	}

	/**
	 * 根据 type 参数执行单条增量添加。
	 */
	private static int executeLinkAddWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.LINK_RW, 1)) {
			return 0;
		}
		LinkNodeType sourceType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		long targetSerial = LongArgumentType.getLong(context, "target_serial");
		return executeLinkUpdate(source, player, sourceType, sourceSerial, targetSerial, LinkUpdateMode.ADD);
	}

	/**
	 * 根据 type 参数执行单条增量移除。
	 */
	private static int executeLinkRemoveWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.LINK_RW, 1)) {
			return 0;
		}
		LinkNodeType sourceType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		long targetSerial = LongArgumentType.getLong(context, "target_serial");
		return executeLinkUpdate(source, player, sourceType, sourceSerial, targetSerial, LinkUpdateMode.REMOVE);
	}

	/**
	 * 查询单个源节点当前关联目标列表。
	 */
	private static int executeLinkGet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		NodeSnapshotQueryService.NodeReadSnapshot readSnapshot = NodeSnapshotQueryService.query(
			source.getLevel(),
			type,
			serial,
			source.hasPermission(RedstoneLinkConfig.currentLinksPrivacyViewPermissionLevel())
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.link.get",
				CommandTreeSupport.typeCommandName(type),
				serial,
				readSnapshot.linksSnapshot().visibleTargetCount(),
				CommandTreeSupport.formatSerialList(readSnapshot.linksSnapshot().visibleTargets())
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * `link set` 批量覆盖设置链接集合。
	 */
	private static int executeLinkSet(
		CommandContext<CommandSourceStack> context,
		LinkNodeType sourceType,
		boolean hasTargets,
		boolean confirmed
	) {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		ServerLevel level = source.getLevel();

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		Set<Long> targets;
		String rawTargets = "";
		int maxTargets = RedstoneLinkConfig.maxTargetsPerSetLinks();
		if (!hasTargets) {
			targets = Set.of();
		} else {
			rawTargets = SerialBatchArgumentType.getSerialBatch(context, "targets");
			int maxInputLength = RedstoneLinkConfig.linkSetMaxInputLength();
			if (rawTargets.length() > maxInputLength) {
				source.sendFailure(Component.translatable("message.redstonelink.link.set.input_too_long", maxInputLength));
				return 0;
			}
			LinkCommandSupport.TargetParseResult parseResult = LinkCommandSupport.parseTargetSerials(rawTargets, maxTargets);
			if (!parseResult.invalidEntries().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.invalid_target_tokens",
						String.join(", ", parseResult.invalidEntries())
					)
				);
				return 0;
			}
			if (parseResult.exceedLimit()) {
				source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
				return 0;
			}
			targets = parseResult.targets();
			if (!parseResult.duplicateEntries().isEmpty()) {
				source.sendSuccess(
					() -> Component.translatable(
						"message.redstonelink.duplicate_targets_deduped",
						CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
					),
					false
				);
			}
		}
		if (targets.size() > maxTargets) {
			source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(2, targets.size(), 64);
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.LINK_RW,
				commandCost
			)
		) {
			return 0;
		}

		LinkNodeType targetType = LinkNodeSemantics.resolveTargetTypeForSource(sourceType);
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
					CommandTreeSupport.formatSerialList(unallocatedTargets)
				)
			);
			return 0;
		}
		if (!retiredTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_retired",
					CommandTreeSupport.formatSerialList(retiredTargets)
				)
			);
			return 0;
		}
		boolean allowOfflineBinding = RedstoneLinkConfig.allowOfflineTargetBinding();
		if (!allowOfflineBinding && !offlineTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.offline_targets_blocked",
					CommandTreeSupport.formatSerialList(offlineTargets)
				)
			);
			return 0;
		}

		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial));
		Set<Long> affectedTargets = new HashSet<>(previousTargets);
		affectedTargets.addAll(targets);
		if (!LinkCommandSupport.checkLinkWriteAllowed(source, level, sourceType, sourceSerial, affectedTargets, targets.size())) {
			return 0;
		}

		if (hasTargets && targets.size() > 1 && !confirmed) {
			String confirmCommand = "redstonelink link set "
				+ CommandTreeSupport.typeCommandName(sourceType)
				+ " "
				+ sourceSerial
				+ " "
				+ rawTargets
				+ " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.set_links.confirm_required",
					targets.size(),
					confirmCommand
				)
			);
			return 0;
		}
		LinkSavedData.ReplaceLinksResult replaceResult = savedData.replaceLinksBySourceType(sourceType, sourceSerial, targets);
		if (replaceResult.addedCount() > 0) {
			Set<Long> addedTargets = new HashSet<>(targets);
			addedTargets.removeAll(previousTargets);
			if (!addedTargets.isEmpty()) {
				InternalDispatchDeltaEvents.publishLinkAttached(
					level,
					sourceType,
					sourceSerial,
					addedTargets,
					ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L)
				);
			}
		}
		if (replaceResult.removedCount() > 0) {
			Set<Long> removedTargets = new HashSet<>(previousTargets);
			removedTargets.removeAll(targets);
			if (!removedTargets.isEmpty()) {
				InternalDispatchDeltaEvents.publishLinkDetached(
					level,
					sourceType,
					sourceSerial,
					removedTargets,
					ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L)
				);
			}
		}

		LinkCommandSupport.syncAffectedNodeLinkSnapshots(level, targetType, previousTargets, targets);
		LinkCommandSupport.syncPlayerItemLinkSnapshot(player, sourceType, sourceSerial);
		final int currentTargetCount = replaceResult.currentCount();
		source.sendSuccess(() -> Component.translatable("message.redstonelink.set_links_done", currentTargetCount), false);
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
	 * 根据 type 参数执行覆盖式 `link set`。
	 */
	private static int executeLinkSetWithTypeArg(
		CommandContext<CommandSourceStack> context,
		boolean hasTargets,
		boolean confirmed
	) {
		CommandSourceStack source = context.getSource();
		LinkNodeType sourceType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		return executeLinkSet(context, sourceType, hasTargets, confirmed);
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
		LinkUpdateMode updateMode
	) {
		ServerLevel level = source.getLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		if (targetSerial <= 0L) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial));
			if (!LinkCommandSupport.checkLinkWriteAllowed(source, level, sourceType, sourceSerial, previousTargets, 0)) {
				return 0;
			}
			int removed = savedData.clearLinksForNode(sourceType, sourceSerial);
			if (removed > 0 && !previousTargets.isEmpty()) {
				InternalDispatchDeltaEvents.publishLinkDetached(
					level,
					sourceType,
					sourceSerial,
					previousTargets,
					ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L)
				);
			}
			LinkCommandSupport.syncPlayerItemLinkSnapshot(player, sourceType, sourceSerial);
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.links_cleared", removed),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		if (updateMode == LinkUpdateMode.REMOVE) {
			Set<Long> previousTargets = savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial);
			if (!previousTargets.contains(targetSerial)) {
				source.sendFailure(Component.translatable("message.redstonelink.link_not_exists"));
				return 0;
			}
			int nextTargetCount = Math.max(0, previousTargets.size() - 1);
			if (!LinkCommandSupport.checkLinkWriteAllowed(
				source,
				level,
				sourceType,
				sourceSerial,
				Set.of(targetSerial),
				nextTargetCount,
				true
			)) {
				return 0;
			}
			boolean removedNow = savedData.removeLinkBySourceType(sourceType, sourceSerial, targetSerial);
			if (!removedNow) {
				source.sendFailure(Component.translatable("message.redstonelink.link_not_exists"));
				return 0;
			}
			InternalDispatchDeltaEvents.publishLinkDetached(
				level,
				sourceType,
				sourceSerial,
				Set.of(targetSerial),
				ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L)
			);
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_removed"), false);
			LinkCommandSupport.syncPlayerItemLinkSnapshot(player, sourceType, sourceSerial);
			return Command.SINGLE_SUCCESS;
		}
		if (updateMode != LinkUpdateMode.ADD) {
			throw new IllegalStateException("Unsupported link update mode: " + updateMode);
		}

		LinkNodeType targetType = LinkNodeSemantics.resolveTargetTypeForSource(sourceType);
		if (!ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, targetType, targetSerial)) {
			return 0;
		}
		boolean targetOffline = savedData.findNode(targetType, targetSerial).isEmpty();
		if (targetOffline && !RedstoneLinkConfig.allowOfflineTargetBinding()) {
			source.sendFailure(Component.translatable("message.redstonelink.offline_targets_blocked", Long.toString(targetSerial)));
			return 0;
		}
		Set<Long> currentTargets = savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial);
		if (currentTargets.contains(targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.link_already_exists"));
			return 0;
		}
		int nextTargetCount = currentTargets.size() + 1;
		if (!LinkCommandSupport.checkLinkWriteAllowed(source, level, sourceType, sourceSerial, Set.of(targetSerial), nextTargetCount)) {
			return 0;
		}

		boolean addedNow = savedData.addLinkBySourceType(sourceType, sourceSerial, targetSerial);
		if (!addedNow) {
			source.sendFailure(Component.translatable("message.redstonelink.link_already_exists"));
			return 0;
		}
		InternalDispatchDeltaEvents.publishLinkAttached(
			level,
			sourceType,
			sourceSerial,
			Set.of(targetSerial),
			ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L)
		);
		source.sendSuccess(() -> Component.translatable("message.redstonelink.link_added"), false);
		LinkCommandSupport.syncPlayerItemLinkSnapshot(player, sourceType, sourceSerial);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 单目标更新模式。
	 */
	private enum LinkUpdateMode {
		ADD,
		REMOVE
	}
}
