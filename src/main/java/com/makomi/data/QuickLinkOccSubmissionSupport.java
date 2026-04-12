package com.makomi.data;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * quick-link OCC 提交适配层。
 * <p>
 * 将 quick-link 的 expected revision 校验与真实应用动作收口，供网络入口与 bench/internal 复用。
 * </p>
 */
public final class QuickLinkOccSubmissionSupport {
	private QuickLinkOccSubmissionSupport() {
	}

	/**
	 * 按显式缓存参数提交一次 quick-link 应用。
	 */
	public static SubmissionResult submit(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		LinkNodeType cacheType,
		String serialCacheExpression,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		return submit(
			commandSource,
			player,
			level,
			targetNodeType,
			targetNodeSerial,
			cacheType,
			serialCacheExpression,
			QuickLinkToolData.ApplyEditMode.REPLACE,
			expectedCoreRevision,
			expectedSourceRevision
		);
	}

	/**
	 * 按显式缓存参数提交一次 quick-link 应用。
	 */
	public static SubmissionResult submit(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		LinkNodeType cacheType,
		String serialCacheExpression,
		QuickLinkToolData.ApplyEditMode applyEditMode,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		if (level == null || targetNodeType == null || targetNodeSerial <= 0L) {
			return SubmissionResult.rejected(QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target"));
		}

		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveTargetConflict(
			LinkSavedData.get(level),
			targetNodeType,
			targetNodeSerial,
			expectedCoreRevision,
			expectedSourceRevision
		);
		if (conflict != null) {
			return SubmissionResult.conflict(
				conflict,
				LinkSavedData.get(level).getLinkedPeersByNodeType(targetNodeType, targetNodeSerial).size()
			);
		}

		QuickLinkApplyService.ApplyFromCacheResult applyResult = QuickLinkApplyService.applyFromCache(
			commandSource,
			player,
			level,
			targetNodeType,
			targetNodeSerial,
			cacheType,
			serialCacheExpression,
			applyEditMode
		);
		return applyResult.feedback().success()
			? SubmissionResult.applied(
				applyResult.feedback(),
				applyResult.affectedSourceCount(),
				applyResult.currentTargetCount()
			)
			: SubmissionResult.rejected(applyResult.feedback());
	}

	/**
	 * quick-link OCC 提交结果。
	 */
	public record SubmissionResult(
		boolean applied,
		LinkOccSupport.OccConflict conflict,
		QuickLinkOperationFeedback feedback,
		int affectedSourceCount,
		int currentTargetCount
	) {
		public SubmissionResult {
			affectedSourceCount = Math.max(0, affectedSourceCount);
			currentTargetCount = Math.max(0, currentTargetCount);
		}

		static SubmissionResult applied(QuickLinkOperationFeedback feedback, int affectedSourceCount, int currentTargetCount) {
			return new SubmissionResult(true, null, feedback, affectedSourceCount, currentTargetCount);
		}

		static SubmissionResult rejected(QuickLinkOperationFeedback feedback) {
			return new SubmissionResult(false, null, feedback, 0, 0);
		}

		static SubmissionResult conflict(LinkOccSupport.OccConflict conflict, int currentTargetCount) {
			return new SubmissionResult(false, conflict, LinkOccSupport.toQuickLinkFeedback(conflict), 0, currentTargetCount);
		}
	}
}
