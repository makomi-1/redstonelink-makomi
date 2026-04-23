package com.makomi.command.bench;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.KeyValueTokenArgumentType;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkSavedDataChannelSupport.ChannelOverride;
import com.makomi.data.QuickLinkOccSubmissionSupport;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.network.PairingOccSubmissionSupport;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * bench/internal OCC 回归命令注册器。
 * <p>
 * 该命令直接调用 pairing / quick-link 的共享 OCC 提交适配层，
 * 用稳定 machine-readable summary 暴露 baseline、applied 与 conflict 结果。
 * </p>
 */
public final class BenchOccCommandRegistry {
	private BenchOccCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench occ` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("occ")
			.then(
				Commands
					.literal("snapshot")
					.then(
						Commands
							.literal("triggerSource")
							.then(Commands.argument("serial", LongArgumentType.longArg(1L)).executes(BenchOccCommandRegistry::executeTriggerSourceSnapshot))
					)
					.then(
						Commands
							.literal("core")
							.then(Commands.argument("serial", LongArgumentType.longArg(1L)).executes(BenchOccCommandRegistry::executeCoreSnapshot))
					)
			)
			.then(
				Commands
					.literal("pairing")
					.then(
						Commands
							.literal("submit")
							.then(
								Commands
									.literal("triggerSource")
									.then(
										Commands.literal("batch").then(
											Commands.argument("serials", SerialBatchArgumentType.serialBatch()).then(
												Commands.literal("core").then(
													Commands.literal("channel_partition").then(
														Commands.argument("partition_size", IntegerArgumentType.integer(1)).then(
															Commands.argument("channel_base", LongArgumentType.longArg(1L)).then(
																Commands
																	.argument("expected_source_revision_spec", KeyValueTokenArgumentType.keyValueToken())
																	.executes(BenchOccCommandRegistry::executeTriggerSourceChannelPartitionSubmit)
															)
														)
													)
												)
											)
										)
									)
									.then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).then(
											Commands
												.literal("core")
												.then(
													Commands.argument("target_serials", SerialBatchArgumentType.serialBatch()).then(
														Commands
															.argument("expected_source_revision_spec", KeyValueTokenArgumentType.keyValueToken())
															.executes(BenchOccCommandRegistry::executeTriggerSourcePairingSubmit)
													)
												)
												.then(
													Commands.literal("channel").then(
														Commands.argument("channel", LongArgumentType.longArg(1L)).then(
															Commands
																.argument("expected_source_revision_spec", KeyValueTokenArgumentType.keyValueToken())
																.executes(BenchOccCommandRegistry::executeTriggerSourceChannelPairingSubmit)
														)
													)
												)
										)
									)
							)
							.then(
								Commands
									.literal("core")
									.then(
										Commands.literal("batch").then(
											Commands.argument("serials", SerialBatchArgumentType.serialBatch()).then(
												Commands.literal("triggerSource").then(
													Commands.literal("channel_partition").then(
														Commands.argument("partition_size", IntegerArgumentType.integer(1)).then(
															Commands.argument("channel_base", LongArgumentType.longArg(1L)).then(
																Commands
																	.argument("expected_core_revision_spec", KeyValueTokenArgumentType.keyValueToken())
																	.executes(BenchOccCommandRegistry::executeCoreChannelPartitionSubmit)
															)
														)
													)
												)
											)
										)
									)
									.then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).then(
											Commands
												.literal("triggerSource")
												.then(
													Commands.argument("source_serials", SerialBatchArgumentType.serialBatch()).then(
														Commands
															.argument("expected_core_revision_spec", KeyValueTokenArgumentType.keyValueToken())
															.executes(BenchOccCommandRegistry::executeCorePairingSubmit)
													)
												)
												.then(
													Commands.literal("channel").then(
														Commands.argument("channel", LongArgumentType.longArg(1L)).then(
															Commands
																.argument("expected_core_revision_spec", KeyValueTokenArgumentType.keyValueToken())
																.executes(BenchOccCommandRegistry::executeCoreChannelPairingSubmit)
														)
													)
												)
										)
									)
							)
					)
			)
			.then(
				Commands
					.literal("quick_link")
					.then(
						Commands
							.literal("apply")
							.then(
								Commands
									.literal("triggerSource")
									.then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).then(
											Commands
												.literal("core")
												.then(
													Commands.argument("target_serials", SerialBatchArgumentType.serialBatch()).then(
														Commands
															.argument("expected_source_revision_spec", KeyValueTokenArgumentType.keyValueToken())
															.executes(BenchOccCommandRegistry::executeTriggerSourceQuickLinkApply)
													)
												)
										)
									)
							)
							.then(
								Commands
									.literal("core")
									.then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).then(
											Commands
												.literal("triggerSource")
												.then(
													Commands.argument("source_serials", SerialBatchArgumentType.serialBatch()).then(
														Commands
															.argument("expected_core_revision_spec", KeyValueTokenArgumentType.keyValueToken())
															.executes(BenchOccCommandRegistry::executeCoreQuickLinkApply)
													)
												)
										)
									)
							)
					)
			);
	}

	/**
	 * 读取 `triggerSource` 的 OCC baseline。
	 */
	private static int executeTriggerSourceSnapshot(CommandContext<CommandSourceStack> context) {
		return executeSnapshot(context, LinkNodeType.TRIGGER_SOURCE);
	}

	/**
	 * 读取 `core` 的 OCC baseline。
	 */
	private static int executeCoreSnapshot(CommandContext<CommandSourceStack> context) {
		return executeSnapshot(context, LinkNodeType.CORE);
	}

	/**
	 * 提交 `triggerSource -> core` 的 pairing OCC 覆盖写入。
	 */
	private static int executeTriggerSourcePairingSubmit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long sourceSerial = LongArgumentType.getLong(context, "serial");
		long expectedSourceRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_source_revision_spec"),
			"expectedSourceRevision"
		);
		if (expectedSourceRevision < 0L) {
			return 0;
		}
		String targetsExpression = SerialBatchArgumentType.getSerialBatch(context, "target_serials");
		PairingOccSubmissionSupport.SubmissionResult result = PairingOccSubmissionSupport.submitTriggerSource(
			source,
			player,
			level,
			sourceSerial,
			targetsExpression,
			expectedSourceRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(
			LinkSavedData.get(level),
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial
		);
		String summary = buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			0L,
			expectedSourceRevision,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.appliedOperationCount(),
			resolvePrimaryOperationFeedbackKey(result.feedbacks()),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 提交 `triggerSource` 视角的频道 pairing 写入。
	 */
	private static int executeTriggerSourceChannelPairingSubmit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long sourceSerial = LongArgumentType.getLong(context, "serial");
		long channel = LongArgumentType.getLong(context, "channel");
		long expectedSourceRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_source_revision_spec"),
			"expectedSourceRevision"
		);
		if (expectedSourceRevision < 0L) {
			return 0;
		}
		PairingOccSubmissionSupport.SubmissionResult result = PairingOccSubmissionSupport.submitTriggerSource(
			source,
			player,
			level,
			sourceSerial,
			LinkConnectionMode.CHANNEL.token(),
			"",
			channel,
			expectedSourceRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(
			LinkSavedData.get(level),
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial
		);
		String summary = buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			0L,
			expectedSourceRevision,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.appliedOperationCount(),
			resolvePrimaryOperationFeedbackKey(result.feedbacks()),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 提交 `triggerSource` 视角的批量频道 partition 写入。
	 */
	private static int executeTriggerSourceChannelPartitionSubmit(CommandContext<CommandSourceStack> context) {
		return executeChannelPartitionSubmit(
			context,
			LinkNodeType.TRIGGER_SOURCE,
			"expectedSourceRevision"
		);
	}

	/**
	 * 提交 `core` 视角的批量频道 partition 写入。
	 */
	private static int executeCoreChannelPartitionSubmit(CommandContext<CommandSourceStack> context) {
		return executeChannelPartitionSubmit(
			context,
			LinkNodeType.CORE,
			"expectedCoreRevision",
			"expectedGraphRevision"
		);
	}

	/**
	 * 提交 `core` 视角的 pairing OCC 覆盖写入。
	 */
	private static int executeCorePairingSubmit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long coreSerial = LongArgumentType.getLong(context, "serial");
		long expectedCoreRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_core_revision_spec"),
			"expectedCoreRevision",
			"expectedGraphRevision"
		);
		if (expectedCoreRevision < 0L) {
			return 0;
		}
		String triggerSourceExpression = SerialBatchArgumentType.getSerialBatch(context, "source_serials");
		PairingOccSubmissionSupport.SubmissionResult result = PairingOccSubmissionSupport.submitCore(
			source,
			player,
			level,
			coreSerial,
			triggerSourceExpression,
			expectedCoreRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(LinkSavedData.get(level), LinkNodeType.CORE, coreSerial);
		String summary = buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.CORE,
			coreSerial,
			expectedCoreRevision,
			0L,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.appliedOperationCount(),
			resolvePrimaryOperationFeedbackKey(result.feedbacks()),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 提交 `core` 视角的频道 pairing 写入。
	 */
	private static int executeCoreChannelPairingSubmit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long coreSerial = LongArgumentType.getLong(context, "serial");
		long channel = LongArgumentType.getLong(context, "channel");
		long expectedCoreRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_core_revision_spec"),
			"expectedCoreRevision",
			"expectedGraphRevision"
		);
		if (expectedCoreRevision < 0L) {
			return 0;
		}
		PairingOccSubmissionSupport.SubmissionResult result = PairingOccSubmissionSupport.submitCore(
			source,
			player,
			level,
			coreSerial,
			LinkConnectionMode.CHANNEL.token(),
			"",
			channel,
			expectedCoreRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(LinkSavedData.get(level), LinkNodeType.CORE, coreSerial);
		String summary = buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.CORE,
			coreSerial,
			expectedCoreRevision,
			0L,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.appliedOperationCount(),
			resolvePrimaryOperationFeedbackKey(result.feedbacks()),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 执行批量频道 partition 提交。
	 */
	private static int executeChannelPartitionSubmit(
		CommandContext<CommandSourceStack> context,
		LinkNodeType nodeType,
		String expectedRevisionKey,
		String... aliasKeys
	) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		String rawSerials = SerialBatchArgumentType.getSerialBatch(context, "serials");
		List<Long> orderedSerials = parseOrderedUniqueSerialBatch(source, rawSerials);
		if (orderedSerials.isEmpty()) {
			return 0;
		}

		int partitionSize = IntegerArgumentType.getInteger(context, "partition_size");
		long channelBase = LongArgumentType.getLong(context, "channel_base");
		long expectedRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, nodeType == LinkNodeType.TRIGGER_SOURCE
				? "expected_source_revision_spec"
				: "expected_core_revision_spec"),
			expectedRevisionKey,
			aliasKeys
		);
		if (expectedRevision < 0L) {
			return 0;
		}

		ChannelPartitionBatchSubmissionResult result = submitChannelPartitionBatch(
			source,
			player,
			level,
			nodeType,
			orderedSerials,
			partitionSize,
			channelBase,
			expectedRevision
		);
		String summary = buildChannelPartitionSummary(
			nodeType,
			orderedSerials.size(),
			partitionSize,
			channelBase,
			nodeType == LinkNodeType.CORE ? expectedRevision : 0L,
			nodeType == LinkNodeType.TRIGGER_SOURCE ? expectedRevision : 0L,
			result
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 提交一批按 partition 递增频道的 bench OCC 写入。
	 */
	private static ChannelPartitionBatchSubmissionResult submitChannelPartitionBatch(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		LinkNodeType nodeType,
		List<Long> orderedSerials,
		int partitionSize,
		long channelBase,
		long expectedRevision
	) {
		if (
			commandSource == null
				|| level == null
				|| nodeType == null
				|| orderedSerials == null
				|| orderedSerials.isEmpty()
				|| partitionSize <= 0
				|| channelBase <= 0L
		) {
			return ChannelPartitionBatchSubmissionResult.rejected("message.redstonelink.invalid_channel");
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (Long serial : orderedSerials) {
			if (serial == null || serial <= 0L) {
				return ChannelPartitionBatchSubmissionResult.rejected("message.redstonelink.invalid_channel");
			}
			LinkOccSupport.OccConflict conflict = nodeType == LinkNodeType.TRIGGER_SOURCE
				? LinkOccSupport.resolveTriggerSourceConflict(savedData, serial, expectedRevision)
				: LinkOccSupport.resolveCoreConflict(savedData, serial, expectedRevision);
			if (conflict != null) {
				return ChannelPartitionBatchSubmissionResult.conflict(conflict);
			}
		}

		List<ChannelOverride> overrides = buildChannelPartitionOverrides(nodeType, orderedSerials, partitionSize, channelBase);
		LinkChannelEditingService.BatchPreparationResult preparationResult = LinkChannelEditingService.prepareConfirmedBatchSetChannel(
			level,
			player,
			overrides,
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			return ChannelPartitionBatchSubmissionResult.rejected(resolvePrimaryOperationFeedbackKey(preparationResult.feedbacks()));
		}

		LinkChannelEditingService.PreparedChannelBatchUpdate plan = preparationResult.plan();
		if (!plan.hasChanges()) {
			return ChannelPartitionBatchSubmissionResult.applied(
				plan.changedChannelNodeCount(),
				plan.changedTriggerSourceCount(),
				0,
				resolvePrimaryOperationFeedbackKey(preparationResult.feedbacks())
			);
		}
		if (
			plan.totalCommandCost() > 0 &&
			!CommandRateLimitService.tryAcquire(commandSource, CommandRateLimitService.CommandGroup.LINK_RW, plan.totalCommandCost())
		) {
			return ChannelPartitionBatchSubmissionResult.rejected("message.redstonelink.command.rate_limit.exceeded");
		}

		LinkChannelEditingService.BatchApplyResult applyResult = LinkChannelEditingService.applyPreparedBatchSetChannel(plan);
		return ChannelPartitionBatchSubmissionResult.applied(
			applyResult.changedChannelNodeCount(),
			plan.changedTriggerSourceCount(),
			applyResult.appliedOperationCount(),
			resolvePrimaryOperationFeedbackKey(preparationResult.feedbacks())
		);
	}

	/**
	 * 将 ordered serial 列表映射为批量频道覆盖。
	 */
	private static List<ChannelOverride> buildChannelPartitionOverrides(
		LinkNodeType nodeType,
		List<Long> orderedSerials,
		int partitionSize,
		long channelBase
	) {
		if (nodeType == null || orderedSerials == null || orderedSerials.isEmpty() || partitionSize <= 0 || channelBase <= 0L) {
			return List.of();
		}
		List<ChannelOverride> overrides = new ArrayList<>(orderedSerials.size());
		for (int index = 0; index < orderedSerials.size(); index++) {
			Long serial = orderedSerials.get(index);
			if (serial == null || serial <= 0L) {
				continue;
			}
			long channel = channelBase + Math.floorDiv(index, partitionSize);
			overrides.add(new ChannelOverride(nodeType, serial, channel));
		}
		return overrides.isEmpty() ? List.of() : List.copyOf(overrides);
	}

	/**
	 * 解析 bench 使用的 ordered serial batch，并做顺序去重。
	 */
	private static List<Long> parseOrderedUniqueSerialBatch(CommandSourceStack source, String rawSerials) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawSerials, 0);
		if (parseResult == null || parseResult.orderedTargets().isEmpty()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] Invalid serial batch: empty resolved set, got " + rawSerials)
			);
			return List.of();
		}
		LinkedHashSet<Long> orderedDistinct = new LinkedHashSet<>(parseResult.orderedTargets());
		if (orderedDistinct.isEmpty()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] Invalid serial batch: empty resolved set, got " + rawSerials)
			);
			return List.of();
		}
		return List.copyOf(orderedDistinct);
	}

	/**
	 * 提交 `triggerSource` 目标的 quick-link OCC 应用。
	 */
	private static int executeTriggerSourceQuickLinkApply(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long sourceSerial = LongArgumentType.getLong(context, "serial");
		long expectedSourceRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_source_revision_spec"),
			"expectedSourceRevision"
		);
		if (expectedSourceRevision < 0L) {
			return 0;
		}
		String targetsExpression = SerialBatchArgumentType.getSerialBatch(context, "target_serials");
		QuickLinkOccSubmissionSupport.SubmissionResult result = QuickLinkOccSubmissionSupport.submit(
			source,
			player,
			level,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			LinkNodeType.CORE,
			targetsExpression,
			0L,
			expectedSourceRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(
			LinkSavedData.get(level),
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial
		);
		String summary = buildQuickLinkSummary(
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			0L,
			expectedSourceRevision,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.affectedSourceCount(),
			result.feedback(),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 提交 `core` 目标的 quick-link OCC 应用。
	 */
	private static int executeCoreQuickLinkApply(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long coreSerial = LongArgumentType.getLong(context, "serial");
		long expectedCoreRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_core_revision_spec"),
			"expectedCoreRevision",
			"expectedGraphRevision"
		);
		if (expectedCoreRevision < 0L) {
			return 0;
		}
		String triggerSourceExpression = SerialBatchArgumentType.getSerialBatch(context, "source_serials");
		QuickLinkOccSubmissionSupport.SubmissionResult result = QuickLinkOccSubmissionSupport.submit(
			source,
			player,
			level,
			LinkNodeType.CORE,
			coreSerial,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceExpression,
			expectedCoreRevision,
			0L
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(LinkSavedData.get(level), LinkNodeType.CORE, coreSerial);
		String summary = buildQuickLinkSummary(
			LinkNodeType.CORE,
			coreSerial,
			expectedCoreRevision,
			0L,
			baseline,
			result.conflict(),
			result.currentTargetCount(),
			result.affectedSourceCount(),
			result.feedback(),
			resolveSubmissionOutcome(result.applied(), result.conflict())
		);
		return sendSubmissionSummary(source, result.applied(), result.conflict() != null, summary);
	}

	/**
	 * 执行一次 baseline 快照读取。
	 */
	private static int executeSnapshot(CommandContext<CommandSourceStack> context, LinkNodeType nodeType) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		long serial = LongArgumentType.getLong(context, "serial");
		if (!savedData.isSerialAllocated(nodeType, serial)) {
			source.sendFailure(
				Component.literal(
					buildSnapshotFailureSummary(nodeType, serial, messageKeyForUnallocated(nodeType))
				)
			);
			return 0;
		}
		if (savedData.isSerialRetired(nodeType, serial)) {
			source.sendFailure(
				Component.literal(
					buildSnapshotFailureSummary(nodeType, serial, messageKeyForRetired(nodeType))
				)
			);
			return 0;
		}
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(savedData, nodeType, serial);
		source.sendSuccess(
			() -> Component.literal(buildSnapshotSummary(nodeType, serial, baseline, savedData.getLinkedPeersByNodeType(nodeType, serial).size())),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析 `key=value` 形式的 revision 参数。
	 */
	static long parseNamedLongSpec(CommandSourceStack source, String rawSpec, String expectedKey, String... aliasKeys) {
		String matchedPrefix = null;
		String primaryPrefix = expectedKey + "=";
		if (rawSpec != null && rawSpec.regionMatches(true, 0, primaryPrefix, 0, primaryPrefix.length())) {
			matchedPrefix = primaryPrefix;
		} else if (rawSpec != null && aliasKeys != null) {
			for (String aliasKey : aliasKeys) {
				String aliasPrefix = aliasKey + "=";
				if (rawSpec.regionMatches(true, 0, aliasPrefix, 0, aliasPrefix.length())) {
					matchedPrefix = aliasPrefix;
					break;
				}
			}
		}
		if (matchedPrefix == null) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid occ spec order: expected " + primaryPrefix + "..., got " + rawSpec
				)
			);
			return -1L;
		}
		String valueText = rawSpec.substring(matchedPrefix.length());
		try {
			return Long.parseLong(valueText);
		} catch (NumberFormatException exception) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid occ spec: expected " + expectedKey + "=<long>, got " + rawSpec
				)
			);
			return -1L;
		}
	}

	/**
	 * 发送 OCC 提交 summary。
	 * <p>
	 * applied/conflict 都视为逻辑命中；只有 rejected 才走命令失败码。
	 * </p>
	 */
	private static int sendSubmissionSummary(
		CommandSourceStack source,
		boolean applied,
		boolean conflict,
		String summary
	) {
		if (applied || conflict) {
			source.sendSuccess(() -> Component.literal(summary), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal(summary));
		return 0;
	}

	/**
	 * 构造 baseline 快照 summary。
	 */
	static String buildSnapshotSummary(
		LinkNodeType nodeType,
		long serial,
		LinkOccSupport.RevisionBaseline baseline,
		int currentTargetCount
	) {
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] occ_snapshot type=%s serial=%d graphRevision=%d sourceRevision=%d coreRevision=%d currentTargetCount=%d",
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			baseline.graphRevision(),
			baseline.sourceRevision(),
			baseline.coreRevision(),
			Math.max(0, currentTargetCount)
		);
	}

	/**
	 * 构造 snapshot 失败 summary。
	 */
	private static String buildSnapshotFailureSummary(LinkNodeType nodeType, long serial, String messageKey) {
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] occ_snapshot outcome=rejected type=%s serial=%d messageKey=%s",
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			formatMessageKey(messageKey)
		);
	}

	/**
	 * 构造 pairing 提交 summary。
	 */
	static String buildPairingSummary(
		String action,
		LinkNodeType nodeType,
		long serial,
		long expectedCoreRevision,
		long expectedSourceRevision,
		LinkOccSupport.RevisionBaseline baseline,
		LinkOccSupport.OccConflict conflict,
		int currentTargetCount,
		int appliedOperationCount,
		String messageKey,
		String outcome
	) {
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] %s outcome=%s type=%s serial=%d expectedCoreRevision=%d expectedSourceRevision=%d currentGraphRevision=%d currentSourceRevision=%d currentCoreRevision=%d appliedOperationCount=%d currentTargetCount=%d messageKey=%s",
			action,
			outcome,
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			conflict == null ? Math.max(0L, expectedCoreRevision) : conflict.expectedCoreRevision(),
			conflict == null ? Math.max(0L, expectedSourceRevision) : conflict.expectedSourceRevision(),
			conflict == null ? baseline.graphRevision() : conflict.currentGraphRevision(),
			conflict == null ? baseline.sourceRevision() : conflict.currentSourceRevision(),
			conflict == null ? baseline.coreRevision() : conflict.currentCoreRevision(),
			Math.max(0, appliedOperationCount),
			Math.max(0, currentTargetCount),
			formatMessageKey(conflict == null ? messageKey : conflict.messageKey())
		);
	}

	/**
	 * 构造 quick-link 提交 summary。
	 */
	static String buildQuickLinkSummary(
		LinkNodeType nodeType,
		long serial,
		long expectedCoreRevision,
		long expectedSourceRevision,
		LinkOccSupport.RevisionBaseline baseline,
		LinkOccSupport.OccConflict conflict,
		int currentTargetCount,
		int affectedSourceCount,
		QuickLinkOperationFeedback feedback,
		String outcome
	) {
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] occ_quick_link_apply outcome=%s type=%s serial=%d expectedCoreRevision=%d expectedSourceRevision=%d currentGraphRevision=%d currentSourceRevision=%d currentCoreRevision=%d affectedSourceCount=%d currentTargetCount=%d messageKey=%s",
			outcome,
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			conflict == null ? Math.max(0L, expectedCoreRevision) : conflict.expectedCoreRevision(),
			conflict == null ? Math.max(0L, expectedSourceRevision) : conflict.expectedSourceRevision(),
			conflict == null ? baseline.graphRevision() : conflict.currentGraphRevision(),
			conflict == null ? baseline.sourceRevision() : conflict.currentSourceRevision(),
			conflict == null ? baseline.coreRevision() : conflict.currentCoreRevision(),
			Math.max(0, affectedSourceCount),
			Math.max(0, currentTargetCount),
			formatMessageKey(conflict == null ? resolveQuickLinkMessageKey(feedback) : conflict.messageKey())
		);
	}

	/**
	 * 构造批量频道 partition 提交 summary。
	 */
	static String buildChannelPartitionSummary(
		LinkNodeType nodeType,
		int requestedCount,
		int partitionSize,
		long channelBase,
		long expectedCoreRevision,
		long expectedSourceRevision,
		ChannelPartitionBatchSubmissionResult result
	) {
		int normalizedRequestedCount = Math.max(0, requestedCount);
		int normalizedPartitionSize = Math.max(1, partitionSize);
		long normalizedChannelBase = Math.max(0L, channelBase);
		long firstChannel = normalizedRequestedCount <= 0 ? 0L : normalizedChannelBase;
		long lastChannel = normalizedRequestedCount <= 0
			? 0L
			: normalizedChannelBase + Math.floorDiv(normalizedRequestedCount - 1, normalizedPartitionSize);
		int channelCount = normalizedRequestedCount <= 0 ? 0 : (int) (lastChannel - firstChannel + 1L);
		LinkOccSupport.OccConflict conflict = result == null ? null : result.conflict();
		String outcome = result == null ? "rejected" : resolveSubmissionOutcome(result.applied(), conflict);
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] occ_channel_partition_submit outcome=%s type=%s requestedCount=%d partitionSize=%d channelBase=%d firstChannel=%d lastChannel=%d channelCount=%d expectedCoreRevision=%d expectedSourceRevision=%d currentGraphRevision=%d currentSourceRevision=%d currentCoreRevision=%d conflictSerial=%d changedChannelNodeCount=%d changedTriggerSourceCount=%d appliedOperationCount=%d messageKey=%s",
			outcome,
			CommandTreeSupport.typeCommandName(nodeType),
			normalizedRequestedCount,
			normalizedPartitionSize,
			normalizedChannelBase,
			firstChannel,
			lastChannel,
			channelCount,
			conflict == null ? Math.max(0L, expectedCoreRevision) : conflict.expectedCoreRevision(),
			conflict == null ? Math.max(0L, expectedSourceRevision) : conflict.expectedSourceRevision(),
			conflict == null ? -1L : conflict.currentGraphRevision(),
			conflict == null ? -1L : conflict.currentSourceRevision(),
			conflict == null ? -1L : conflict.currentCoreRevision(),
			conflict == null ? 0L : conflict.targetNodeSerial(),
			result == null ? 0 : Math.max(0, result.changedChannelNodeCount()),
			result == null ? 0 : Math.max(0, result.changedTriggerSourceCount()),
			result == null ? 0 : Math.max(0, result.appliedOperationCount()),
			formatMessageKey(conflict == null ? (result == null ? "-" : result.messageKey()) : conflict.messageKey())
		);
	}

	/**
	 * 解析 bench summary 使用的提交结果类别。
	 */
	static String resolveSubmissionOutcome(boolean applied, LinkOccSupport.OccConflict conflict) {
		if (applied) {
			return "applied";
		}
		return conflict == null ? "rejected" : "conflict";
	}

	/**
	 * 解析命令反馈列表的主 message key。
	 */
	static String resolvePrimaryOperationFeedbackKey(List<LinkSetExecutionService.OperationFeedback> feedbacks) {
		if (feedbacks == null || feedbacks.isEmpty()) {
			return "-";
		}
		for (LinkSetExecutionService.OperationFeedback feedback : feedbacks) {
			if (feedback != null && !feedback.success()) {
				return formatMessageKey(feedback.messageKey());
			}
		}
		for (int index = feedbacks.size() - 1; index >= 0; index--) {
			LinkSetExecutionService.OperationFeedback feedback = feedbacks.get(index);
			if (feedback != null && feedback.messageKey() != null && !feedback.messageKey().isBlank()) {
				return formatMessageKey(feedback.messageKey());
			}
		}
		return "-";
	}

	/**
	 * 解析 quick-link 主 message key。
	 */
	static String resolveQuickLinkMessageKey(QuickLinkOperationFeedback feedback) {
		if (feedback == null || feedback.messageKey() == null || feedback.messageKey().isBlank()) {
			return "-";
		}
		return formatMessageKey(feedback.messageKey());
	}

	/**
	 * 统一格式化可空 message key。
	 */
	static String formatMessageKey(String messageKey) {
		if (messageKey == null || messageKey.isBlank()) {
			return "-";
		}
		return messageKey;
	}

	/**
	 * 根据节点类型返回“未分配”提示键。
	 */
	private static String messageKeyForUnallocated(LinkNodeType nodeType) {
		return nodeType == LinkNodeType.TRIGGER_SOURCE
			? "message.redstonelink.source_serial_unallocated"
			: "message.redstonelink.target_serial_unallocated";
	}

	/**
	 * 根据节点类型返回“已退役”提示键。
	 */
	private static String messageKeyForRetired(LinkNodeType nodeType) {
		return nodeType == LinkNodeType.TRIGGER_SOURCE
			? "message.redstonelink.source_serial_retired"
			: "message.redstonelink.target_serial_retired";
	}

	/**
	 * 批量频道 partition 提交结果。
	 */
	record ChannelPartitionBatchSubmissionResult(
		boolean applied,
		LinkOccSupport.OccConflict conflict,
		int changedChannelNodeCount,
		int changedTriggerSourceCount,
		int appliedOperationCount,
		String messageKey
	) {
		ChannelPartitionBatchSubmissionResult {
			changedChannelNodeCount = Math.max(0, changedChannelNodeCount);
			changedTriggerSourceCount = Math.max(0, changedTriggerSourceCount);
			appliedOperationCount = Math.max(0, appliedOperationCount);
			messageKey = formatMessageKey(messageKey);
		}

		static ChannelPartitionBatchSubmissionResult applied(
			int changedChannelNodeCount,
			int changedTriggerSourceCount,
			int appliedOperationCount,
			String messageKey
		) {
			return new ChannelPartitionBatchSubmissionResult(
				true,
				null,
				changedChannelNodeCount,
				changedTriggerSourceCount,
				appliedOperationCount,
				messageKey
			);
		}

		static ChannelPartitionBatchSubmissionResult rejected(String messageKey) {
			return new ChannelPartitionBatchSubmissionResult(false, null, 0, 0, 0, messageKey);
		}

		static ChannelPartitionBatchSubmissionResult conflict(LinkOccSupport.OccConflict conflict) {
			return new ChannelPartitionBatchSubmissionResult(false, conflict, 0, 0, 0, conflict == null ? "-" : conflict.messageKey());
		}
	}
}
