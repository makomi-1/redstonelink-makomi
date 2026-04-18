package com.makomi.command.link;

import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkSavedDataChannelSupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 频道配置编辑服务。
 * <p>
 * 该服务只负责把“节点切到频道模式并指定频道号”的请求，
 * 转换成对应的普通 `triggerSource -> core` 边同步计划；
 * 真正的连接行为仍完全落在普通边上。
 * </p>
 */
public final class LinkChannelEditingService {
	private LinkChannelEditingService() {
	}

	/**
	 * 准备一次“切到频道模式”的编辑计划。
	 */
	public static PreparationResult prepareConfirmedSetChannel(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType editedType,
		long editedSerial,
		long channel,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		if (level == null || editedType == null || channel < 0L) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.invalid_channel")
			);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		if (editedSerial <= 0L || !savedData.isSerialAllocated(editedType, editedSerial)) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure(
					editedType == LinkNodeType.TRIGGER_SOURCE
						? "message.redstonelink.source_serial_unallocated"
						: "message.redstonelink.target_serial_unallocated",
					Long.toString(editedSerial)
				)
			);
		}
		if (savedData.isSerialRetired(editedType, editedSerial)) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure(
					editedType == LinkNodeType.TRIGGER_SOURCE
						? "message.redstonelink.source_serial_retired"
						: "message.redstonelink.target_serial_retired",
					Long.toString(editedSerial)
				)
			);
		}

		Set<Long> affectedTriggerSources = resolveAffectedTriggerSources(savedData, editedType, editedSerial, channel);
		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations = new ArrayList<>();
		int totalCommandCost = 0;
		for (Long triggerSourceSerial : affectedTriggerSources) {
			if (triggerSourceSerial == null || triggerSourceSerial <= 0L) {
				continue;
			}
			Set<Long> nextTargets = LinkSavedDataChannelSupport.resolveDesiredTargetsForTriggerSourceWithOverride(
				savedData,
				triggerSourceSerial,
				editedType,
				editedSerial,
				channel > 0L ? LinkConnectionMode.CHANNEL : LinkConnectionMode.SERIAL,
				channel
			);
			LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplaceResolvedTargets(
				level,
				player,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				nextTargets,
				List.of(),
				hasLimitedBypassPermission,
				hasProtectedBypassPermission,
				false,
				false
			);
			if (!preparationResult.successful()) {
				return new PreparationResult(null, preparationResult.feedbacks());
			}
			if (!preparationResult.operation().previousTargets().equals(preparationResult.operation().targets())) {
				preparedOperations.add(preparationResult.operation());
				totalCommandCost = CoreLinkEditingService.saturatingAdd(
					totalCommandCost,
					preparationResult.operation().commandCost()
				);
			}
		}

		LinkConnectionMode currentMode = savedData.getConnectionMode(editedType, editedSerial);
		long currentChannel = savedData.getChannel(editedType, editedSerial);
		boolean channelChanged = channel > 0L
			? currentMode != LinkConnectionMode.CHANNEL || currentChannel != channel
			: currentMode != LinkConnectionMode.SERIAL || currentChannel != 0L;
		if (channelChanged && totalCommandCost <= 0) {
			totalCommandCost = 1;
		}
		return PreparationResult.success(
			new PreparedChannelUpdate(
				level,
				player,
				editedType,
				editedSerial,
				channel,
				preparedOperations,
				totalCommandCost,
				channelChanged
			),
			List.of()
		);
	}

	/**
	 * 执行已准备好的频道切换计划。
	 */
	public static ApplyResult applyPreparedSetChannel(PreparedChannelUpdate plan) {
		if (plan == null) {
			return new ApplyResult(0, 0);
		}
		LinkSavedData savedData = LinkSavedData.get(plan.level());
		if (plan.channel() > 0L) {
			LinkSavedDataChannelSupport.putChannelConfig(savedData, plan.editedType(), plan.editedSerial(), plan.channel());
		} else {
			LinkSavedDataChannelSupport.clearChannelConfig(savedData, plan.editedType(), plan.editedSerial());
		}
		LinkCommandSupport.BatchLinkSnapshotSyncCollector batchSyncCollector = new LinkCommandSupport.BatchLinkSnapshotSyncCollector(
			plan.level()
		);
		int appliedOperationCount = 0;
		for (LinkSetExecutionService.PreparedReplaceOperation preparedOperation : plan.preparedOperations()) {
			LinkSetExecutionService.applyPreparedReplace(preparedOperation, batchSyncCollector);
			appliedOperationCount++;
		}
		batchSyncCollector.flush();
		return new ApplyResult(
			appliedOperationCount,
			savedData.getLinkedPeersByNodeType(plan.editedType(), plan.editedSerial()).size()
		);
	}

	/**
	 * 解析本次频道配置变化会影响哪些 triggerSource。
	 */
	private static Set<Long> resolveAffectedTriggerSources(
		LinkSavedData savedData,
		LinkNodeType editedType,
		long editedSerial,
		long nextChannel
	) {
		LinkedHashSet<Long> affectedTriggerSources = new LinkedHashSet<>();
		if (editedType == LinkNodeType.TRIGGER_SOURCE) {
			affectedTriggerSources.add(editedSerial);
			return affectedTriggerSources;
		}

		affectedTriggerSources.addAll(savedData.getLinkedTriggerSourcesByCore(editedSerial));
		long currentChannel = savedData.getChannel(LinkNodeType.CORE, editedSerial);
		if (savedData.getConnectionMode(LinkNodeType.CORE, editedSerial) == LinkConnectionMode.CHANNEL) {
			affectedTriggerSources.addAll(savedData.getChannelMembers(LinkNodeType.TRIGGER_SOURCE, currentChannel));
		}
		affectedTriggerSources.addAll(savedData.getChannelMembers(LinkNodeType.TRIGGER_SOURCE, nextChannel));
		return affectedTriggerSources;
	}

	/**
	 * 准备阶段结果。
	 */
	public record PreparationResult(
		PreparedChannelUpdate plan,
		List<LinkSetExecutionService.OperationFeedback> feedbacks
	) {
		public PreparationResult {
			feedbacks = List.copyOf(feedbacks == null ? List.of() : feedbacks);
		}

		/**
		 * 返回失败结果。
		 */
		public static PreparationResult failure(LinkSetExecutionService.OperationFeedback feedback) {
			return new PreparationResult(null, List.of(feedback));
		}

		/**
		 * 返回成功结果。
		 */
		public static PreparationResult success(
			PreparedChannelUpdate plan,
			List<LinkSetExecutionService.OperationFeedback> feedbacks
		) {
			return new PreparationResult(plan, feedbacks);
		}

		/**
		 * @return 当前是否准备成功
		 */
		public boolean successful() {
			return plan != null;
		}
	}

	/**
	 * 频道配置编辑计划。
	 */
	public record PreparedChannelUpdate(
		ServerLevel level,
		ServerPlayer player,
		LinkNodeType editedType,
		long editedSerial,
		long channel,
		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations,
		int totalCommandCost,
		boolean channelChanged
	) {
		public PreparedChannelUpdate {
			preparedOperations = List.copyOf(preparedOperations == null ? List.of() : preparedOperations);
			totalCommandCost = Math.max(0, totalCommandCost);
		}

		/**
		 * @return 当前计划是否会改动任何配置或普通边
		 */
		public boolean hasChanges() {
			return channelChanged || !preparedOperations.isEmpty();
		}

		/**
		 * @return 当前配置落地后的一跳对侧数量
		 */
		public int currentLinkedPeerCount() {
			return preparedOperations.isEmpty() ? 0 : preparedOperations.get(preparedOperations.size() - 1).targets().size();
		}

		/**
		 * @return 当前影响到的 triggerSource 写操作数
		 */
		public int changedTriggerSourceCount() {
			return preparedOperations.size();
		}
	}

	/**
	 * 执行阶段结果。
	 */
	public record ApplyResult(int appliedOperationCount, int currentLinkedPeerCount) {}
}
