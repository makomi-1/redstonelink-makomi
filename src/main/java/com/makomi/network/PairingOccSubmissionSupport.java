package com.makomi.network;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.link.CoreLinkEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
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
		String targetsExpression,
		long expectedSourceRevision
	) {
		if (commandSource == null || level == null) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")),
				0
			);
		}

		LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplace(
			level,
			player,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			targetsExpression,
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
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
		feedbacks.addAll(applyResult.feedbacks());
		return SubmissionResult.applied(feedbacks, 1, applyResult.currentTargetCount());
	}

	/**
	 * 提交 `core` 视角的 pairing 覆盖写入。
	 */
	public static SubmissionResult submitCore(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String triggerSourceExpression,
		long expectedCoreRevision
	) {
		if (commandSource == null || level == null) {
			return SubmissionResult.rejected(
				List.of(LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")),
				0
			);
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
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			commandSource.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel())
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
