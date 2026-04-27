package com.makomi.network;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.link.CoreLinkEditingService;
import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.data.RepeaterGraphSnapshotSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * pairing OCC 提交适配层。
 * <p>
 * 把网络入口与 bench/internal 入口收口到同一套“准备 -> OCC 校验 -> 真实写入”闭环。
 * </p>
 */
public final class PairingOccSubmissionSupport {
	private PairingOccSubmissionSupport() {
	}

	/**
	 * 提交 `triggerSource` 视角的 pairing 覆盖写入。
	 */
	public static SubmissionResult submitTriggerSource(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long sourceSerial,
		String connectionModeToken,
		String targetsExpression,
		long channel,
		long expectedSourceRevision
	) {
		if (commandSource == null || level == null) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")),
				0
			);
		}
		LinkConnectionMode connectionMode = LinkConnectionMode.fromToken(connectionModeToken);
		if (connectionMode == LinkConnectionMode.CHANNEL) {
			return submitTriggerSourceChannel(
				commandSource,
				player,
				level,
				sourceSerial,
				channel,
				expectedSourceRevision
			);
		}

		LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplace(
			level,
			player,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			targetsExpression,
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			return SubmissionResult.rejected(preparationResult.feedbacks(), 0);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveTriggerSourceConflict(savedData, sourceSerial, expectedSourceRevision);
		if (conflict != null) {
			return SubmissionResult.conflict(
				conflict,
				savedData.getLinkedCoresByTriggerSource(sourceSerial).size()
			);
		}

		LinkSetExecutionService.PreparedReplaceOperation operation = preparationResult.operation();
		if (
			!CommandRateLimitService.tryAcquire(
				commandSource,
				CommandRateLimitService.CommandGroup.LINK_RW,
				operation.commandCost()
			)
		) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.command.rate_limit.exceeded")),
				0
			);
		}

		List<LinkSetExecutionService.OperationFeedback> feedbacks = new ArrayList<>(preparationResult.feedbacks());
		LinkSetExecutionService.ApplyResult applyResult = LinkSetExecutionService.applyPreparedReplace(operation);
		syncRepeaterDisplaysIfNeeded(level, sourceSerial);
		feedbacks.addAll(applyResult.feedbacks());
		return SubmissionResult.applied(feedbacks, 1, applyResult.currentTargetCount());
	}

	/**
	 * 兼容旧入口：默认按 serial 模式提交。
	 */
	public static SubmissionResult submitTriggerSource(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long sourceSerial,
		String targetsExpression,
		long expectedSourceRevision
	) {
		return submitTriggerSource(
			commandSource,
			player,
			level,
			sourceSerial,
			LinkConnectionMode.SERIAL.token(),
			targetsExpression,
			0L,
			expectedSourceRevision
		);
	}

	/**
	 * 提交 `core` 视角的 pairing 覆盖写入。
	 */
	public static SubmissionResult submitCore(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String connectionModeToken,
		String triggerSourceExpression,
		long channel,
		long expectedCoreRevision
	) {
		if (commandSource == null || level == null) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")),
				0
			);
		}
		LinkConnectionMode connectionMode = LinkConnectionMode.fromToken(connectionModeToken);
		if (connectionMode == LinkConnectionMode.CHANNEL) {
			return submitCoreChannel(commandSource, player, level, coreSerial, channel, expectedCoreRevision);
		}

		PairingNetworkServerHandlerSupport.CorePairingParseResult parseResult = PairingNetworkServerHandlerSupport.parseCorePairingTriggerSources(
			triggerSourceExpression
		);
		if (!parseResult.invalidEntries().isEmpty()) {
			return SubmissionResult.rejected(
				List.of(
					LinkSetExecutionService.OperationFeedback.failure(
						"message.redstonelink.invalid_target_tokens",
						String.join(", ", parseResult.invalidEntries())
					)
				),
				0
			);
		}
		if (parseResult.exceedLimit()) {
			return SubmissionResult.rejected(
				List.of(
					LinkSetExecutionService.OperationFeedback.failure(
						"message.redstonelink.too_many_targets",
						Integer.toString(RedstoneLinkConfig.general().maxTargetsPerSetLinks())
					)
				),
				0
			);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		java.util.Set<Long> currentTriggerSources = new java.util.LinkedHashSet<>(savedData.getLinkedTriggerSourcesByCore(coreSerial));
		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveCoreConflict(savedData, coreSerial, expectedCoreRevision);
		if (conflict != null) {
			return SubmissionResult.conflict(
				conflict,
				savedData.getLinkedTriggerSourcesByCore(coreSerial).size()
			);
		}

		CoreLinkEditingService.PreparationResult preparationResult = CoreLinkEditingService.prepareConfirmedReplace(
			level,
			player,
			coreSerial,
			parseResult.orderedTriggerSources(),
			parseResult.duplicateEntries(),
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			return SubmissionResult.rejected(preparationResult.feedbacks(), 0);
		}

		List<LinkSetExecutionService.OperationFeedback> feedbacks = new ArrayList<>(preparationResult.feedbacks());
		CoreLinkEditingService.PreparedReplacePlan plan = preparationResult.plan();
		if (!plan.hasChanges()) {
			feedbacks.add(
				LinkSetExecutionService.OperationFeedback.success(
					"message.redstonelink.core_pairing.apply.no_changes",
					Long.toString(coreSerial),
					Integer.toString(plan.currentTriggerSourceCount())
				)
			);
			return SubmissionResult.applied(feedbacks, 0, plan.currentTriggerSourceCount());
		}

		if (!CommandRateLimitService.tryAcquire(commandSource, CommandRateLimitService.CommandGroup.LINK_RW, plan.totalCommandCost())) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.command.rate_limit.exceeded")),
				0
			);
		}

		CoreLinkEditingService.ApplyResult applyResult = CoreLinkEditingService.applyPreparedReplace(plan);
		syncRepeaterDisplaysIfNeeded(level, coreSerial);
		syncRepeaterDisplaysIfNeeded(level, currentTriggerSources);
		syncRepeaterDisplaysIfNeeded(level, new java.util.LinkedHashSet<>(parseResult.orderedTriggerSources()));
		feedbacks.add(
			LinkSetExecutionService.OperationFeedback.success(
				"message.redstonelink.core_pairing.apply.done",
				Long.toString(coreSerial),
				Integer.toString(applyResult.currentTriggerSourceCount()),
				Integer.toString(applyResult.appliedOperationCount())
			)
		);
		return SubmissionResult.applied(feedbacks, applyResult.appliedOperationCount(), applyResult.currentTriggerSourceCount());
	}

	/**
	 * 兼容旧入口：默认按 serial 模式提交。
	 */
	public static SubmissionResult submitCore(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String triggerSourceExpression,
		long expectedCoreRevision
	) {
		return submitCore(
			commandSource,
			player,
			level,
			coreSerial,
			LinkConnectionMode.SERIAL.token(),
			triggerSourceExpression,
			0L,
			expectedCoreRevision
		);
	}

	/**
	 * 提交 triggerSource 频道配置写入。
	 */
	private static SubmissionResult submitTriggerSourceChannel(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long sourceSerial,
		long channel,
		long expectedSourceRevision
	) {
		LinkSavedData savedData = LinkSavedData.get(level);
		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveTriggerSourceConflict(savedData, sourceSerial, expectedSourceRevision);
		if (conflict != null) {
			return SubmissionResult.conflict(conflict, savedData.getLinkedCoresByTriggerSource(sourceSerial).size());
		}

		LinkChannelEditingService.PreparationResult preparationResult = LinkChannelEditingService.prepareConfirmedSetChannel(
			level,
			player,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			channel,
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			return SubmissionResult.rejected(preparationResult.feedbacks(), 0);
		}

		List<LinkSetExecutionService.OperationFeedback> feedbacks = new ArrayList<>(preparationResult.feedbacks());
		LinkChannelEditingService.PreparedChannelUpdate plan = preparationResult.plan();
		if (!plan.hasChanges()) {
			feedbacks.add(
				LinkSetExecutionService.OperationFeedback.success(
					"message.redstonelink.pairing.channel.no_changes.trigger_source",
					Long.toString(sourceSerial),
					Long.toString(channel),
					Integer.toString(savedData.getLinkedCoresByTriggerSource(sourceSerial).size())
				)
			);
			return SubmissionResult.applied(feedbacks, 0, savedData.getLinkedCoresByTriggerSource(sourceSerial).size());
		}
		if (
			plan.totalCommandCost() > 0 &&
			!CommandRateLimitService.tryAcquire(commandSource, CommandRateLimitService.CommandGroup.LINK_RW, plan.totalCommandCost())
		) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.command.rate_limit.exceeded")),
				0
			);
		}

		LinkChannelEditingService.ApplyResult applyResult = LinkChannelEditingService.applyPreparedSetChannel(plan);
		syncRepeaterDisplaysIfNeeded(level, sourceSerial);
		feedbacks.add(
			LinkSetExecutionService.OperationFeedback.success(
				"message.redstonelink.pairing.channel.done.trigger_source",
				Long.toString(sourceSerial),
				Long.toString(channel),
				Integer.toString(applyResult.currentLinkedPeerCount())
			)
		);
		return SubmissionResult.applied(feedbacks, applyResult.appliedOperationCount(), applyResult.currentLinkedPeerCount());
	}

	/**
	 * 提交 core 频道配置写入。
	 */
	private static SubmissionResult submitCoreChannel(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		long channel,
		long expectedCoreRevision
	) {
		LinkSavedData savedData = LinkSavedData.get(level);
		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveCoreConflict(savedData, coreSerial, expectedCoreRevision);
		if (conflict != null) {
			return SubmissionResult.conflict(conflict, savedData.getLinkedTriggerSourcesByCore(coreSerial).size());
		}

		LinkChannelEditingService.PreparationResult preparationResult = LinkChannelEditingService.prepareConfirmedSetChannel(
			level,
			player,
			LinkNodeType.CORE,
			coreSerial,
			channel,
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			com.makomi.command.CommandPermissionCompat.hasPermission(commandSource, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (!preparationResult.successful()) {
			return SubmissionResult.rejected(preparationResult.feedbacks(), 0);
		}

		List<LinkSetExecutionService.OperationFeedback> feedbacks = new ArrayList<>(preparationResult.feedbacks());
		LinkChannelEditingService.PreparedChannelUpdate plan = preparationResult.plan();
		if (!plan.hasChanges()) {
			feedbacks.add(
				LinkSetExecutionService.OperationFeedback.success(
					"message.redstonelink.pairing.channel.no_changes.core",
					Long.toString(coreSerial),
					Long.toString(channel),
					Integer.toString(savedData.getLinkedTriggerSourcesByCore(coreSerial).size())
				)
			);
			return SubmissionResult.applied(feedbacks, 0, savedData.getLinkedTriggerSourcesByCore(coreSerial).size());
		}
		if (
			plan.totalCommandCost() > 0 &&
			!CommandRateLimitService.tryAcquire(commandSource, CommandRateLimitService.CommandGroup.LINK_RW, plan.totalCommandCost())
		) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.command.rate_limit.exceeded")),
				0
			);
		}

		LinkChannelEditingService.ApplyResult applyResult = LinkChannelEditingService.applyPreparedSetChannel(plan);
		syncRepeaterDisplaysIfNeeded(level, coreSerial);
		feedbacks.add(
			LinkSetExecutionService.OperationFeedback.success(
				"message.redstonelink.pairing.channel.done.core",
				Long.toString(coreSerial),
				Long.toString(channel),
				Integer.toString(applyResult.currentLinkedPeerCount())
			)
		);
		return SubmissionResult.applied(feedbacks, applyResult.appliedOperationCount(), applyResult.currentLinkedPeerCount());
	}

	/**
	 * 若当前节点是转发器统一序号，则把在线方块实体与物品摘要同步回图真值。
	 */
	private static void syncRepeaterDisplaysIfNeeded(ServerLevel level, long serial) {
		if (level == null || serial <= 0L) {
			return;
		}
		if (!LinkSavedData.get(level).isRepeaterSerial(serial)) {
			return;
		}
		RepeaterGraphSnapshotSupport.syncOnlineRepeaterDisplays(level.getServer(), serial);
	}

	/**
	 * 若序号集合中包含转发器统一序号，则逐个同步其在线方块实体与物品摘要。
	 */
	private static void syncRepeaterDisplaysIfNeeded(ServerLevel level, java.util.Set<Long> serials) {
		if (level == null || serials == null || serials.isEmpty()) {
			return;
		}
		for (Long serial : serials) {
			if (serial != null) {
				syncRepeaterDisplaysIfNeeded(level, serial);
			}
		}
	}

	/**
	 * pairing 提交结果。
	 */
	public record SubmissionResult(
		boolean applied,
		LinkOccSupport.OccConflict conflict,
		List<LinkSetExecutionService.OperationFeedback> feedbacks,
		int appliedOperationCount,
		int currentTargetCount
	) {
		public SubmissionResult {
			feedbacks = List.copyOf(feedbacks == null ? List.of() : feedbacks);
			appliedOperationCount = Math.max(0, appliedOperationCount);
			currentTargetCount = Math.max(0, currentTargetCount);
		}

		static SubmissionResult applied(
			List<LinkSetExecutionService.OperationFeedback> feedbacks,
			int appliedOperationCount,
			int currentTargetCount
		) {
			return new SubmissionResult(true, null, feedbacks, appliedOperationCount, currentTargetCount);
		}

		static SubmissionResult rejected(List<LinkSetExecutionService.OperationFeedback> feedbacks, int currentTargetCount) {
			return new SubmissionResult(false, null, feedbacks, 0, currentTargetCount);
		}

		static SubmissionResult conflict(LinkOccSupport.OccConflict conflict, int currentTargetCount) {
			return new SubmissionResult(
				false,
				conflict,
				List.of(LinkOccSupport.toOperationFeedback(conflict)),
				0,
				currentTargetCount
			);
		}
	}
}
