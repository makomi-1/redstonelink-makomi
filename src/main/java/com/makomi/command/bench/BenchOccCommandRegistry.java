package com.makomi.command.bench;

import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.KeyValueTokenArgumentType;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.data.QuickLinkOccSubmissionSupport;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.network.PairingOccSubmissionSupport;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
															.argument("expected_graph_revision_spec", KeyValueTokenArgumentType.keyValueToken())
															.executes(BenchOccCommandRegistry::executeCorePairingSubmit)
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
															.argument("expected_graph_revision_spec", KeyValueTokenArgumentType.keyValueToken())
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
	 * 提交 `core` 视角的 pairing OCC 覆盖写入。
	 */
	private static int executeCorePairingSubmit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		long coreSerial = LongArgumentType.getLong(context, "serial");
		long expectedGraphRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_graph_revision_spec"),
			"expectedGraphRevision"
		);
		if (expectedGraphRevision < 0L) {
			return 0;
		}
		String triggerSourceExpression = SerialBatchArgumentType.getSerialBatch(context, "source_serials");
		PairingOccSubmissionSupport.SubmissionResult result = PairingOccSubmissionSupport.submitCore(
			source,
			player,
			level,
			coreSerial,
			triggerSourceExpression,
			expectedGraphRevision
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(LinkSavedData.get(level), LinkNodeType.CORE, coreSerial);
		String summary = buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.CORE,
			coreSerial,
			expectedGraphRevision,
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
		long expectedGraphRevision = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "expected_graph_revision_spec"),
			"expectedGraphRevision"
		);
		if (expectedGraphRevision < 0L) {
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
			expectedGraphRevision,
			0L
		);
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(LinkSavedData.get(level), LinkNodeType.CORE, coreSerial);
		String summary = buildQuickLinkSummary(
			LinkNodeType.CORE,
			coreSerial,
			expectedGraphRevision,
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
	static long parseNamedLongSpec(CommandSourceStack source, String rawSpec, String expectedKey) {
		String prefix = expectedKey + "=";
		if (rawSpec == null || !rawSpec.regionMatches(true, 0, prefix, 0, prefix.length())) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid occ spec order: expected " + prefix + "..., got " + rawSpec
				)
			);
			return -1L;
		}
		String valueText = rawSpec.substring(prefix.length());
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
			"[RedstoneLink/Bench] occ_snapshot type=%s serial=%d graphRevision=%d sourceRevision=%d currentTargetCount=%d",
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			baseline.graphRevision(),
			baseline.sourceRevision(),
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
		long expectedGraphRevision,
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
			"[RedstoneLink/Bench] %s outcome=%s type=%s serial=%d expectedGraphRevision=%d expectedSourceRevision=%d currentGraphRevision=%d currentSourceRevision=%d appliedOperationCount=%d currentTargetCount=%d messageKey=%s",
			action,
			outcome,
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			conflict == null ? Math.max(0L, expectedGraphRevision) : conflict.expectedGraphRevision(),
			conflict == null ? Math.max(0L, expectedSourceRevision) : conflict.expectedSourceRevision(),
			conflict == null ? baseline.graphRevision() : conflict.currentGraphRevision(),
			conflict == null ? baseline.sourceRevision() : conflict.currentSourceRevision(),
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
		long expectedGraphRevision,
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
			"[RedstoneLink/Bench] occ_quick_link_apply outcome=%s type=%s serial=%d expectedGraphRevision=%d expectedSourceRevision=%d currentGraphRevision=%d currentSourceRevision=%d affectedSourceCount=%d currentTargetCount=%d messageKey=%s",
			outcome,
			CommandTreeSupport.typeCommandName(nodeType),
			serial,
			conflict == null ? Math.max(0L, expectedGraphRevision) : conflict.expectedGraphRevision(),
			conflict == null ? Math.max(0L, expectedSourceRevision) : conflict.expectedSourceRevision(),
			conflict == null ? baseline.graphRevision() : conflict.currentGraphRevision(),
			conflict == null ? baseline.sourceRevision() : conflict.currentSourceRevision(),
			Math.max(0, affectedSourceCount),
			Math.max(0, currentTargetCount),
			formatMessageKey(conflict == null ? resolveQuickLinkMessageKey(feedback) : conflict.messageKey())
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
}
