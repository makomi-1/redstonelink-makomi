package com.makomi.command.link;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.privacy.CurrentLinksPrivacyCommandRegistry;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeAliasServerSupport;
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
			source.hasPermission(RedstoneLinkConfig.privacy().viewPermissionLevel())
		);
		String displaySerialText = NodeAliasServerSupport.resolveDisplayText(source.getLevel(), type, serial);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.link.get",
				CommandTreeSupport.typeCommandName(type),
				displaySerialText,
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
		if (sourceType == LinkNodeType.CORE) {
			return executeCoreLinkSet(context, hasTargets, confirmed);
		}

		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		ServerLevel level = source.getLevel();

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		String rawTargets = "";
		if (!hasTargets) {
		} else {
			rawTargets = SerialBatchArgumentType.getSerialBatch(context, "targets");
		}

		LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplace(
			level,
			player,
			sourceType,
			sourceSerial,
			rawTargets,
			source.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			source.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			sendOperationFeedbacks(source, preparationResult.feedbacks());
			return 0;
		}

		sendOperationFeedbacks(source, preparationResult.feedbacks());
		LinkSetExecutionService.PreparedReplaceOperation operation = preparationResult.operation();
		if (hasTargets && operation.targets().size() > 1 && !confirmed) {
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
					operation.targets().size(),
					confirmCommand
				)
			);
			return 0;
		}

		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.LINK_RW,
				operation.commandCost()
			)
		) {
			return 0;
		}

		LinkSetExecutionService.ApplyResult applyResult = LinkSetExecutionService.applyPreparedReplace(operation);
		sendOperationFeedbacks(source, applyResult.feedbacks());
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * `link set core` 的高层编辑入口。
	 * <p>
	 * 该入口仅接受“以 core 为观察中心”的写法，真正落库时仍统一拆成
	 * 多条 `triggerSource -> core` 正向覆盖写入。
	 * </p>
	 */
	private static int executeCoreLinkSet(
		CommandContext<CommandSourceStack> context,
		boolean hasTargets,
		boolean confirmed
	) {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		ServerLevel level = source.getLevel();
		long coreSerial = LongArgumentType.getLong(context, "source_serial");
		String rawTargets = hasTargets ? SerialBatchArgumentType.getSerialBatch(context, "targets") : "";

		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (rawTargets.length() > maxInputLength) {
			source.sendFailure(
				Component.translatable("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength))
			);
			return 0;
		}

		CoreLinkEditingService.ParseResult parseResult = CoreLinkEditingService.parseTriggerSourcesExpression(rawTargets);
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
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.too_many_targets",
					Integer.toString(RedstoneLinkConfig.general().maxTargetsPerSetLinks())
				)
			);
			return 0;
		}

		CoreLinkEditingService.PreparationResult preparationResult = CoreLinkEditingService.prepareConfirmedReplace(
			level,
			player,
			coreSerial,
			parseResult.orderedTriggerSources(),
			parseResult.duplicateEntries(),
			source.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			source.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		sendOperationFeedbacks(source, preparationResult.feedbacks());
		if (!preparationResult.successful()) {
			return 0;
		}

		CoreLinkEditingService.PreparedReplacePlan plan = preparationResult.plan();
		if (hasTargets && plan.desiredTriggerSourceCount() > 1 && !confirmed) {
			String confirmCommand = "redstonelink link set core " + coreSerial + " " + rawTargets + " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.set_links.confirm_required",
					plan.desiredTriggerSourceCount(),
					confirmCommand
				)
			);
			return 0;
		}

		if (!plan.hasChanges()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.core_pairing.apply.no_changes",
					Long.toString(coreSerial),
					Integer.toString(plan.currentTriggerSourceCount())
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.LINK_RW,
				plan.totalCommandCost()
			)
		) {
			return 0;
		}

		CoreLinkEditingService.ApplyResult applyResult = CoreLinkEditingService.applyPreparedReplace(plan);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.core_pairing.apply.done",
				Long.toString(coreSerial),
				Integer.toString(applyResult.currentTriggerSourceCount()),
				Integer.toString(applyResult.appliedOperationCount())
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 按命令反馈语义发送结构化写入结果。
	 */
	private static void sendOperationFeedbacks(
		CommandSourceStack source,
		List<LinkSetExecutionService.OperationFeedback> feedbacks
	) {
		if (source == null || feedbacks == null || feedbacks.isEmpty()) {
			return;
		}
		for (LinkSetExecutionService.OperationFeedback feedback : feedbacks) {
			if (feedback == null || feedback.messageKey() == null || feedback.messageKey().isBlank()) {
				continue;
			}
			Object[] args = feedback.messageArgs().toArray();
			if (feedback.success()) {
				source.sendSuccess(() -> Component.translatable(feedback.messageKey(), args), false);
				continue;
			}
			source.sendFailure(Component.translatable(feedback.messageKey(), args));
		}
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
		if (sourceType == LinkNodeType.CORE) {
			return executeCoreLinkUpdate(source, player, sourceSerial, targetSerial, updateMode);
		}

		ServerLevel level = source.getLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}
		LinkNodeType targetType = LinkNodeType.CORE;

		if (targetSerial <= 0L) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(sourceSerial));
			if (!LinkCommandSupport.checkLinkWriteAllowed(source, level, sourceType, sourceSerial, previousTargets, 0)) {
				return 0;
			}
			LinkSetExecutionService.applyPreparedReplace(
				LinkSetExecutionService.createPreparedReplaceOperation(
					level,
					player,
					sourceType,
					sourceSerial,
					targetType,
					previousTargets,
					Set.of(),
					List.of(),
					1
				)
			);
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.links_cleared", previousTargets.size()),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		if (updateMode == LinkUpdateMode.REMOVE) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(sourceSerial));
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
			Set<Long> nextTargets = new HashSet<>(previousTargets);
			nextTargets.remove(targetSerial);
			LinkSetExecutionService.applyPreparedReplace(
				LinkSetExecutionService.createPreparedReplaceOperation(
					level,
					player,
					sourceType,
					sourceSerial,
					targetType,
					previousTargets,
					nextTargets,
					List.of(),
					1
				)
			);
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_removed"), false);
			return Command.SINGLE_SUCCESS;
		}
		if (updateMode != LinkUpdateMode.ADD) {
			throw new IllegalStateException("Unsupported link update mode: " + updateMode);
		}

		if (!ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, targetType, targetSerial)) {
			return 0;
		}
		boolean targetOffline = savedData.findNode(targetType, targetSerial).isEmpty();
		if (targetOffline && !RedstoneLinkConfig.general().allowOfflineTargetBinding()) {
			source.sendFailure(Component.translatable("message.redstonelink.offline_targets_blocked", Long.toString(targetSerial)));
			return 0;
		}
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(sourceSerial));
		if (previousTargets.contains(targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.link_already_exists"));
			return 0;
		}
		int nextTargetCount = previousTargets.size() + 1;
		if (!LinkCommandSupport.checkLinkWriteAllowed(source, level, sourceType, sourceSerial, Set.of(targetSerial), nextTargetCount)) {
			return 0;
		}

		Set<Long> nextTargets = new HashSet<>(previousTargets);
		nextTargets.add(targetSerial);
		LinkSetExecutionService.applyPreparedReplace(
			LinkSetExecutionService.createPreparedReplaceOperation(
				level,
				player,
				sourceType,
				sourceSerial,
				targetType,
				previousTargets,
				nextTargets,
				targetOffline ? List.of(targetSerial) : List.of(),
				1
			)
		);
		source.sendSuccess(() -> Component.translatable("message.redstonelink.link_added"), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 处理 `link add/remove core ...` 的高层编辑请求。
	 */
	private static int executeCoreLinkUpdate(
		CommandSourceStack source,
		ServerPlayer player,
		long coreSerial,
		long triggerSourceSerial,
		LinkUpdateMode updateMode
	) {
		ServerLevel level = source.getLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, LinkNodeType.CORE, coreSerial)) {
			return 0;
		}
		if (!ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
			return 0;
		}

		Set<Long> currentTriggerSources = new HashSet<>(savedData.getLinkedTriggerSourcesByCore(coreSerial));
		if (updateMode == LinkUpdateMode.REMOVE && !currentTriggerSources.contains(triggerSourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.link_not_exists"));
			return 0;
		}
		if (updateMode == LinkUpdateMode.ADD && currentTriggerSources.contains(triggerSourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.link_already_exists"));
			return 0;
		}

		Set<Long> nextTriggerSources = new HashSet<>(currentTriggerSources);
		if (updateMode == LinkUpdateMode.REMOVE) {
			nextTriggerSources.remove(triggerSourceSerial);
		} else if (updateMode == LinkUpdateMode.ADD) {
			nextTriggerSources.add(triggerSourceSerial);
		} else {
			throw new IllegalStateException("Unsupported link update mode: " + updateMode);
		}

		CoreLinkEditingService.PreparationResult preparationResult = CoreLinkEditingService.prepareConfirmedReplace(
			level,
			player,
			coreSerial,
			new ArrayList<>(nextTriggerSources),
			List.of(),
			source.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			source.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		sendOperationFeedbacks(source, preparationResult.feedbacks());
		if (!preparationResult.successful()) {
			return 0;
		}

		CoreLinkEditingService.applyPreparedReplace(preparationResult.plan());
		source.sendSuccess(
			() -> Component.translatable(
				updateMode == LinkUpdateMode.ADD ? "message.redstonelink.link_added" : "message.redstonelink.link_removed"
			),
			false
		);
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
