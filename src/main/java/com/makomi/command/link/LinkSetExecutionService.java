package com.makomi.command.link;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.InternalDispatchDeltaEvents;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkSavedDataChannelSupport;
import com.makomi.data.LinkWriteControlService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * `link set` 已确认覆盖写入执行服务。
 * <p>
 * 该服务只负责“已通过来源选择且无需再次 confirm”的覆盖写入闭环：
 * 解析、校验、写控判定、写入、副作用同步与结构化反馈。
 * </p>
 * <p>
 * 命令层是否允许执行、是否要求二次确认、如何展示反馈，由上层调用方决定。
 * </p>
 */
public final class LinkSetExecutionService {
	private LinkSetExecutionService() {
	}

	/**
	 * 准备一次已确认的覆盖写入操作。
	 *
	 * @param level 服务端世界
	 * @param player 玩家上下文；仅用于后续物品快照同步，可为 {@code null}
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param rawTargetsExpression 原始目标表达式；空表示清空连接
	 * @param hasLimitedBypassPermission 是否具备 limited 模式越权权限
	 * @param hasProtectedBypassPermission 是否具备 protected 越权权限
	 * @return 准备结果；失败时携带失败反馈
	 */
	public static PreparationResult prepareConfirmedReplace(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		String rawTargetsExpression,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		String rawTargets = rawTargetsExpression == null ? "" : rawTargetsExpression.trim();
		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (rawTargets.length() > maxInputLength) {
			return PreparationResult.failure(
				OperationFeedback.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength))
			);
		}

		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		Set<Long> targets = Set.of();
		List<Long> duplicateEntries = List.of();
		if (!rawTargets.isEmpty()) {
			LinkCommandSupport.TargetParseResult parseResult = LinkCommandSupport.parseTargetSerials(rawTargets, maxTargets);
			if (!parseResult.invalidEntries().isEmpty()) {
				return PreparationResult.failure(
					OperationFeedback.failure("message.redstonelink.invalid_target_tokens", String.join(", ", parseResult.invalidEntries()))
				);
			}
			if (parseResult.exceedLimit()) {
				return PreparationResult.failure(
					OperationFeedback.failure("message.redstonelink.too_many_targets", Integer.toString(maxTargets))
				);
			}
			targets = Set.copyOf(parseResult.targets());
			duplicateEntries = List.copyOf(parseResult.duplicateEntries());
		}
		return prepareConfirmedReplaceResolvedTargets(
			level,
			player,
			sourceType,
			sourceSerial,
			targets,
			duplicateEntries,
			hasLimitedBypassPermission,
			hasProtectedBypassPermission,
			true,
			true,
			Set.of()
		);
	}

	/**
	 * 基于结构化目标集合准备一次已确认的覆盖写入操作。
	 *
	 * @param level 服务端世界
	 * @param player 玩家上下文；仅用于后续物品快照同步，可为 {@code null}
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetSerials 结构化目标集合；空集合表示清空连接
	 * @param hasLimitedBypassPermission 是否具备 limited 模式越权权限
	 * @param hasProtectedBypassPermission 是否具备 protected 模式越权权限
	 * @return 准备结果；失败时携带失败反馈
	 */
	public static PreparationResult prepareConfirmedReplace(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> targetSerials,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		return prepareConfirmedReplace(
			level,
			player,
			sourceType,
			sourceSerial,
			targetSerials,
			hasLimitedBypassPermission,
			hasProtectedBypassPermission,
			Set.of()
		);
	}

	/**
	 * 基于结构化目标集合准备一次已确认的覆盖写入操作，并允许同一批请求里即将切回 serial 的目标暂时越过频道目标校验。
	 *
	 * @param level 服务端世界
	 * @param player 玩家上下文；仅用于后续物品快照同步，可为 {@code null}
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetSerials 结构化目标集合；空集合表示清空连接
	 * @param hasLimitedBypassPermission 是否具备 limited 模式越权权限
	 * @param hasProtectedBypassPermission 是否具备 protected 模式越权权限
	 * @param channelModeTargetsAllowedByRequest 同一请求中允许暂时保留 channel 真值、但会在实际应用前切回 serial 的目标集合
	 * @return 准备结果；失败时携带失败反馈
	 */
	public static PreparationResult prepareConfirmedReplace(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> targetSerials,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission,
		Set<Long> channelModeTargetsAllowedByRequest
	) {
		return prepareConfirmedReplaceResolvedTargets(
			level,
			player,
			sourceType,
			sourceSerial,
			normalizePositiveTargets(targetSerials),
			List.of(),
			hasLimitedBypassPermission,
			hasProtectedBypassPermission,
			true,
			true,
			channelModeTargetsAllowedByRequest
		);
	}

	/**
	 * 共享的覆盖写入准备主流程。
	 * <p>
	 * 同时服务于命令字符串入口与结构化目标集合入口，统一收口：
	 * 来源校验、目标校验、写控判定、命令成本计算与预备反馈。
	 * </p>
	 */
	static PreparationResult prepareConfirmedReplaceResolvedTargets(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> targetSerials,
		List<Long> duplicateEntries,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission,
		boolean switchSourceToSerialModeBeforeApply,
		boolean requireSerialTargets,
		Set<Long> channelModeTargetsAllowedByRequest
	) {
		if (level == null || sourceType == null) {
			return PreparationResult.failure(OperationFeedback.failure("message.redstonelink.permission.insufficient"));
		}
		if (sourceType != LinkNodeType.TRIGGER_SOURCE) {
			return PreparationResult.failure(OperationFeedback.failure("message.redstonelink.permission.insufficient"));
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		if (sourceSerial <= 0L || !savedData.isSerialAllocated(sourceType, sourceSerial)) {
			return PreparationResult.failure(
				OperationFeedback.failure("message.redstonelink.source_serial_unallocated", Long.toString(sourceSerial))
			);
		}
		if (savedData.isSerialRetired(sourceType, sourceSerial)) {
			return PreparationResult.failure(
				OperationFeedback.failure("message.redstonelink.source_serial_retired", Long.toString(sourceSerial))
			);
		}

		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		Set<Long> targets = normalizePositiveTargets(targetSerials);
		Set<Long> allowedChannelModeTargets = normalizePositiveTargets(channelModeTargetsAllowedByRequest);
		if (targets.size() > maxTargets) {
			return PreparationResult.failure(
				OperationFeedback.failure("message.redstonelink.too_many_targets", Integer.toString(maxTargets))
			);
		}

		LinkNodeType targetType = LinkNodeSemantics.resolveTargetTypeForSource(sourceType);
		if (targetType == null) {
			return PreparationResult.failure(OperationFeedback.failure("message.redstonelink.permission.insufficient"));
		}

		List<Long> unallocatedTargets = new ArrayList<>();
		List<Long> retiredTargets = new ArrayList<>();
		List<Long> offlineTargets = new ArrayList<>();
		List<Long> channelModeTargets = new ArrayList<>();
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
			if (
				requireSerialTargets &&
				savedData.getConnectionMode(targetType, targetSerial) == LinkConnectionMode.CHANNEL &&
				!allowedChannelModeTargets.contains(targetSerial)
			) {
				channelModeTargets.add(targetSerial);
			}
		}
		if (!unallocatedTargets.isEmpty()) {
			return PreparationResult.failure(
				OperationFeedback.failure(
					"message.redstonelink.invalid_target_unallocated",
					CommandTreeSupport.formatSerialList(unallocatedTargets)
				)
			);
		}
		if (!retiredTargets.isEmpty()) {
			return PreparationResult.failure(
				OperationFeedback.failure(
					"message.redstonelink.invalid_target_retired",
					CommandTreeSupport.formatSerialList(retiredTargets)
				)
			);
		}
		if (!channelModeTargets.isEmpty()) {
			return PreparationResult.failure(
				OperationFeedback.failure(
					"message.redstonelink.invalid_target_channel_mode",
					CommandTreeSupport.formatSerialList(channelModeTargets)
				)
			);
		}

		boolean allowOfflineBinding = RedstoneLinkConfig.general().allowOfflineTargetBinding();
		if (!allowOfflineBinding && !offlineTargets.isEmpty()) {
			return PreparationResult.failure(
				OperationFeedback.failure(
					"message.redstonelink.offline_targets_blocked",
					CommandTreeSupport.formatSerialList(offlineTargets)
				)
			);
		}

		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(sourceSerial));
		Set<Long> affectedTargets = new HashSet<>(previousTargets);
		affectedTargets.addAll(targets);
		LinkWriteControlService.WriteDecision writeDecision = LinkWriteControlService.evaluate(
			level,
			sourceType,
			sourceSerial,
			affectedTargets,
			targets.size(),
			hasLimitedBypassPermission,
			hasProtectedBypassPermission
		);
		if (!writeDecision.allowed()) {
			return PreparationResult.failure(feedbackFromWriteDecision(writeDecision));
		}

		List<OperationFeedback> preparationFeedbacks = new ArrayList<>();
		if (!duplicateEntries.isEmpty()) {
			preparationFeedbacks.add(
				OperationFeedback.success(
					"message.redstonelink.duplicate_targets_deduped",
					CommandTreeSupport.formatSerialCollection(duplicateEntries)
				)
			);
		}

		PreparedReplaceOperation operation = new PreparedReplaceOperation(
			level,
			player,
			sourceType,
			sourceSerial,
			targetType,
			Set.copyOf(targets),
			Set.copyOf(previousTargets),
			List.copyOf(offlineTargets),
			CommandRateLimitService.computeBatchCost(2, targets.size(), 64),
			switchSourceToSerialModeBeforeApply
		);
		return PreparationResult.success(operation, preparationFeedbacks);
	}

	/**
	 * 结构化入口的目标集合归一化：仅保留正整数序号并去重。
	 */
	private static Set<Long> normalizePositiveTargets(Set<Long> targetSerials) {
		if (targetSerials == null || targetSerials.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalizedTargets = new HashSet<>();
		for (Long serial : targetSerials) {
			if (serial != null && serial > 0L) {
				normalizedTargets.add(serial);
			}
		}
		return normalizedTargets.isEmpty() ? Set.of() : Set.copyOf(normalizedTargets);
	}

	/**
	 * 基于已完成校验的目标集合构造共享覆盖写入操作。
	 * <p>
	 * 供 `link add/remove/clear`、quick-link、bench 等入口在保留各自校验口径的前提下，
	 * 统一复用同一条 replace/delta/snapshot 执行闭环。
	 * </p>
	 */
	public static PreparedReplaceOperation createPreparedReplaceOperation(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> previousTargets,
		Set<Long> nextTargets,
		List<Long> offlineTargets,
		int commandCost
	) {
		return createPreparedReplaceOperation(
			level,
			player,
			sourceType,
			sourceSerial,
			targetType,
			previousTargets,
			nextTargets,
			offlineTargets,
			commandCost,
			false
		);
	}

	/**
	 * 基于已完成校验的目标集合构造“切回 serial 模式”的覆盖写入操作。
	 */
	public static PreparedReplaceOperation createPreparedSerialReplaceOperation(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> previousTargets,
		Set<Long> nextTargets,
		List<Long> offlineTargets,
		int commandCost
	) {
		return createPreparedReplaceOperation(
			level,
			player,
			sourceType,
			sourceSerial,
			targetType,
			previousTargets,
			nextTargets,
			offlineTargets,
			commandCost,
			true
		);
	}

	/**
	 * 基于已完成校验的目标集合构造共享覆盖写入操作，并可选在应用前清理来源频道模式。
	 */
	static PreparedReplaceOperation createPreparedReplaceOperation(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> previousTargets,
		Set<Long> nextTargets,
		List<Long> offlineTargets,
		int commandCost,
		boolean switchSourceToSerialModeBeforeApply
	) {
		return new PreparedReplaceOperation(
			level,
			player,
			sourceType,
			sourceSerial,
			targetType,
			normalizePositiveTargets(nextTargets),
			normalizePositiveTargets(previousTargets),
			offlineTargets,
			commandCost,
			switchSourceToSerialModeBeforeApply
		);
	}

	/**
	 * 执行一次已准备好的覆盖写入。
	 *
	 * @param operation 已准备好的写入操作
	 * @return 执行结果与后置反馈
	 */
	public static ApplyResult applyPreparedReplace(PreparedReplaceOperation operation) {
		return applyPreparedReplace(operation, null);
	}

	/**
	 * 执行一次已准备好的覆盖写入，并可选登记批量后置同步。
	 *
	 * @param operation 已准备好的写入操作
	 * @param batchSyncCollector 批量同步收集器；传 `null` 时保持原有即时同步
	 * @return 执行结果与后置反馈
	 */
	public static ApplyResult applyPreparedReplace(
		PreparedReplaceOperation operation,
		LinkCommandSupport.BatchLinkSnapshotSyncCollector batchSyncCollector
	) {
		if (operation == null) {
			return new ApplyResult(0, List.of(OperationFeedback.failure("message.redstonelink.permission.insufficient")));
		}

		LinkSavedData savedData = LinkSavedData.get(operation.level());
		if (operation.sourceType() != LinkNodeType.TRIGGER_SOURCE || operation.targetType() != LinkNodeType.CORE) {
			return new ApplyResult(0, List.of(OperationFeedback.failure("message.redstonelink.permission.insufficient")));
		}
		if (operation.switchSourceToSerialModeBeforeApply()) {
			LinkSavedDataChannelSupport.clearChannelConfig(savedData, LinkNodeType.TRIGGER_SOURCE, operation.sourceSerial());
		}

		LinkSavedData.ReplaceLinksResult replaceResult = savedData.replaceTriggerSourceTargets(
			operation.sourceSerial(),
			operation.targets()
		);
		ActivatableTargetBlockEntity.EventMeta eventMeta = ActivatableTargetBlockEntity.EventMeta.of(
			operation.level().getGameTime(),
			0,
			0L
		);

		if (replaceResult.addedCount() > 0) {
			Set<Long> addedTargets = new HashSet<>(operation.targets());
			addedTargets.removeAll(operation.previousTargets());
			if (!addedTargets.isEmpty()) {
				InternalDispatchDeltaEvents.publishLinkAttached(
					operation.level(),
					operation.sourceType(),
					operation.sourceSerial(),
					addedTargets,
					eventMeta
				);
			}
		}
		if (replaceResult.removedCount() > 0) {
			Set<Long> removedTargets = new HashSet<>(operation.previousTargets());
			removedTargets.removeAll(operation.targets());
			if (!removedTargets.isEmpty()) {
				InternalDispatchDeltaEvents.publishLinkDetached(
					operation.level(),
					operation.sourceType(),
					operation.sourceSerial(),
					removedTargets,
					eventMeta
				);
			}
		}

		registerPostWriteSync(operation, batchSyncCollector);

		List<OperationFeedback> feedbacks = new ArrayList<>();
		feedbacks.add(
			OperationFeedback.success("message.redstonelink.set_links_done", Integer.toString(replaceResult.currentCount()))
		);
		if (!operation.offlineTargets().isEmpty()) {
			feedbacks.add(
				OperationFeedback.success("message.redstonelink.offline_targets_saved", formatOfflineTargets(operation.offlineTargets()))
			);
		}
		RedstoneLinkAdvancementService.awardConstellationIfThresholdReached(
			operation.player(),
			operation.previousTargets().size(),
			replaceResult.currentCount()
		);
		return new ApplyResult(replaceResult.currentCount(), List.copyOf(feedbacks));
	}

	/**
	 * 统一处理写入后的节点与物品快照同步。
	 */
	private static void registerPostWriteSync(
		PreparedReplaceOperation operation,
		LinkCommandSupport.BatchLinkSnapshotSyncCollector batchSyncCollector
	) {
		if (operation == null) {
			return;
		}
		if (batchSyncCollector != null) {
			batchSyncCollector.collectPreparedReplace(operation);
			return;
		}
		LinkCommandSupport.syncAffectedNodeLinkSnapshots(
			operation.level(),
			operation.targetType(),
			operation.previousTargets(),
			operation.targets()
		);
		LinkCommandSupport.syncPlayerItemLinkSnapshot(operation.player(), operation.sourceType(), operation.sourceSerial());
	}

	/**
	 * 将写控拒绝结果映射为结构化反馈。
	 */
	static OperationFeedback feedbackFromWriteDecision(LinkWriteControlService.WriteDecision writeDecision) {
		if (writeDecision == null || writeDecision.allowed()) {
			return OperationFeedback.success("", List.of());
		}
		if (writeDecision.denyReason() == LinkWriteControlService.DenyReason.READONLY) {
			return OperationFeedback.failure("message.redstonelink.write_control.deny.readonly");
		}
		return OperationFeedback.failure("message.redstonelink.permission.insufficient");
	}

	/**
	 * 按命令现有口径拼接离线目标提示文本。
	 */
	private static String formatOfflineTargets(List<Long> offlineTargets) {
		if (offlineTargets == null || offlineTargets.isEmpty()) {
			return "-";
		}
		return offlineTargets.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElse("-");
	}

	/**
	 * 准备阶段结果。
	 */
	public record PreparationResult(PreparedReplaceOperation operation, List<OperationFeedback> feedbacks) {
		public PreparationResult {
			feedbacks = List.copyOf(feedbacks == null ? List.of() : feedbacks);
		}

		/**
		 * 返回失败结果。
		 */
		public static PreparationResult failure(OperationFeedback feedback) {
			return new PreparationResult(null, List.of(feedback));
		}

		/**
		 * 返回成功结果。
		 */
		public static PreparationResult success(PreparedReplaceOperation operation, List<OperationFeedback> feedbacks) {
			return new PreparationResult(operation, feedbacks);
		}

		/**
		 * @return 当前是否准备成功
		 */
		public boolean successful() {
			return operation != null;
		}
	}

	/**
	 * 已准备好的覆盖写入操作。
	 */
	public record PreparedReplaceOperation(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targets,
		Set<Long> previousTargets,
		List<Long> offlineTargets,
		int commandCost,
		boolean switchSourceToSerialModeBeforeApply
	) {
		public PreparedReplaceOperation {
			targets = Set.copyOf(targets == null ? Set.of() : targets);
			previousTargets = Set.copyOf(previousTargets == null ? Set.of() : previousTargets);
			offlineTargets = List.copyOf(offlineTargets == null ? List.of() : offlineTargets);
			commandCost = Math.max(1, commandCost);
		}
	}

	/**
	 * 执行阶段结果。
	 */
	public record ApplyResult(int currentTargetCount, List<OperationFeedback> feedbacks) {
		public ApplyResult {
			feedbacks = List.copyOf(feedbacks == null ? List.of() : feedbacks);
		}
	}

	/**
	 * 结构化操作反馈。
	 */
	public record OperationFeedback(boolean success, String messageKey, List<String> messageArgs) {
		public OperationFeedback {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		/**
		 * 构建失败反馈。
		 */
		public static OperationFeedback failure(String messageKey, String... messageArgs) {
			return new OperationFeedback(false, messageKey, List.of(messageArgs));
		}

		/**
		 * 构建失败反馈。
		 */
		public static OperationFeedback failure(String messageKey, List<String> messageArgs) {
			return new OperationFeedback(false, messageKey, messageArgs);
		}

		/**
		 * 构建成功反馈。
		 */
		public static OperationFeedback success(String messageKey, String... messageArgs) {
			return new OperationFeedback(true, messageKey, List.of(messageArgs));
		}

		/**
		 * 构建成功反馈。
		 */
		public static OperationFeedback success(String messageKey, List<String> messageArgs) {
			return new OperationFeedback(true, messageKey, messageArgs);
		}
	}
}
