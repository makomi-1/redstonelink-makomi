package com.makomi.command.link;

import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkSavedDataChannelSupport.ChannelOverride;
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
		BatchPreparationResult batchPreparation = prepareConfirmedBatchSetChannel(
			level,
			player,
			List.of(new ChannelOverride(editedType, editedSerial, channel)),
			hasLimitedBypassPermission,
			hasProtectedBypassPermission
		);
		if (!batchPreparation.successful()) {
			return new PreparationResult(null, batchPreparation.feedbacks());
		}
		PreparedChannelBatchUpdate batchPlan = batchPreparation.plan();
		boolean channelChanged = batchPlan.changedOverrides().stream().anyMatch((override) -> override.nodeType() == editedType && override.serial() == editedSerial);
		return PreparationResult.success(
			new PreparedChannelUpdate(
				level,
				player,
				editedType,
				editedSerial,
				channel,
				batchPlan.preparedOperations(),
				batchPlan.totalCommandCost(),
				channelChanged
			),
			batchPreparation.feedbacks()
		);
	}

	/**
	 * 准备一批频道配置覆盖计划。
	 */
	public static BatchPreparationResult prepareConfirmedBatchSetChannel(
		ServerLevel level,
		ServerPlayer player,
		List<ChannelOverride> overrides,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		if (level == null || overrides == null || overrides.isEmpty()) {
			return BatchPreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.invalid_channel")
			);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		List<ChannelOverride> normalizedOverrides = normalizeOverrides(overrides);
		if (normalizedOverrides.isEmpty()) {
			return BatchPreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.invalid_channel")
			);
		}
		for (ChannelOverride override : normalizedOverrides) {
			LinkSetExecutionService.OperationFeedback validationFeedback = validateEditedNode(savedData, override);
			if (validationFeedback != null) {
				return BatchPreparationResult.failure(validationFeedback);
			}
		}

		Set<Long> affectedTriggerSources = resolveAffectedTriggerSources(savedData, normalizedOverrides);
		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations = new ArrayList<>();
		int totalCommandCost = 0;
		for (Long triggerSourceSerial : affectedTriggerSources) {
			if (triggerSourceSerial == null || triggerSourceSerial <= 0L) {
				continue;
			}
			Set<Long> nextTargets = LinkSavedDataChannelSupport.resolveDesiredTargetsForTriggerSourceWithOverrides(
				savedData,
				triggerSourceSerial,
				normalizedOverrides
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
				return new BatchPreparationResult(null, preparationResult.feedbacks());
			}
			if (!preparationResult.operation().previousTargets().equals(preparationResult.operation().targets())) {
				preparedOperations.add(preparationResult.operation());
				totalCommandCost = CoreLinkEditingService.saturatingAdd(
					totalCommandCost,
					preparationResult.operation().commandCost()
				);
			}
		}

		List<ChannelOverride> changedOverrides = collectChangedOverrides(savedData, normalizedOverrides);
		if (!changedOverrides.isEmpty() && totalCommandCost <= 0) {
			totalCommandCost = 1;
		}
		return BatchPreparationResult.success(
			new PreparedChannelBatchUpdate(
				level,
				player,
				normalizedOverrides,
				changedOverrides,
				preparedOperations,
				totalCommandCost
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
		BatchApplyResult batchApplyResult = applyPreparedBatchSetChannel(
			new PreparedChannelBatchUpdate(
				plan.level(),
				plan.player(),
				List.of(new ChannelOverride(plan.editedType(), plan.editedSerial(), plan.channel())),
				plan.channelChanged() ? List.of(new ChannelOverride(plan.editedType(), plan.editedSerial(), plan.channel())) : List.of(),
				plan.preparedOperations(),
				plan.totalCommandCost()
			)
		);
		LinkSavedData savedData = LinkSavedData.get(plan.level());
		return new ApplyResult(
			batchApplyResult.appliedOperationCount(),
			savedData.getLinkedPeersByNodeType(plan.editedType(), plan.editedSerial()).size()
		);
	}

	/**
	 * 执行已准备好的批量频道切换计划。
	 */
	public static BatchApplyResult applyPreparedBatchSetChannel(PreparedChannelBatchUpdate plan) {
		if (plan == null) {
			return new BatchApplyResult(0, 0);
		}
		LinkSavedData savedData = LinkSavedData.get(plan.level());
		for (ChannelOverride override : plan.overrides()) {
			if (override.channel() > 0L) {
				LinkSavedDataChannelSupport.putChannelConfig(savedData, override.nodeType(), override.serial(), override.channel());
			} else {
				LinkSavedDataChannelSupport.clearChannelConfig(savedData, override.nodeType(), override.serial());
			}
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
		return new BatchApplyResult(
			appliedOperationCount,
			plan.changedOverrides().size()
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
	 * 解析整批频道配置变化会影响哪些 triggerSource。
	 */
	private static Set<Long> resolveAffectedTriggerSources(LinkSavedData savedData, List<ChannelOverride> overrides) {
		LinkedHashSet<Long> affectedTriggerSources = new LinkedHashSet<>();
		for (ChannelOverride override : overrides) {
			if (override == null || !override.valid()) {
				continue;
			}
			affectedTriggerSources.addAll(
				resolveAffectedTriggerSources(savedData, override.nodeType(), override.serial(), override.channel())
			);
		}
		return affectedTriggerSources;
	}

	private static List<ChannelOverride> normalizeOverrides(List<ChannelOverride> overrides) {
		List<ChannelOverride> normalizedOverrides = new ArrayList<>();
		LinkedHashSet<String> seenNodeKeys = new LinkedHashSet<>();
		for (int index = overrides.size() - 1; index >= 0; index--) {
			ChannelOverride override = overrides.get(index);
			if (override == null || !override.valid()) {
				continue;
			}
			String nodeKey = buildNodeKey(override.nodeType(), override.serial());
			if (!seenNodeKeys.add(nodeKey)) {
				continue;
			}
			normalizedOverrides.add(0, override);
		}
		return List.copyOf(normalizedOverrides);
	}

	private static LinkSetExecutionService.OperationFeedback validateEditedNode(LinkSavedData savedData, ChannelOverride override) {
		if (savedData == null || override == null || !override.valid()) {
			return LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.invalid_channel");
		}
		if (!savedData.isSerialAllocated(override.nodeType(), override.serial())) {
			return LinkSetExecutionService.OperationFeedback.failure(
				override.nodeType() == LinkNodeType.TRIGGER_SOURCE
					? "message.redstonelink.source_serial_unallocated"
					: "message.redstonelink.target_serial_unallocated",
				Long.toString(override.serial())
			);
		}
		if (savedData.isSerialRetired(override.nodeType(), override.serial())) {
			return LinkSetExecutionService.OperationFeedback.failure(
				override.nodeType() == LinkNodeType.TRIGGER_SOURCE
					? "message.redstonelink.source_serial_retired"
					: "message.redstonelink.target_serial_retired",
				Long.toString(override.serial())
			);
		}
		return null;
	}

	private static List<ChannelOverride> collectChangedOverrides(LinkSavedData savedData, List<ChannelOverride> overrides) {
		List<ChannelOverride> changedOverrides = new ArrayList<>();
		for (ChannelOverride override : overrides) {
			LinkConnectionMode currentMode = savedData.getConnectionMode(override.nodeType(), override.serial());
			long currentChannel = savedData.getChannel(override.nodeType(), override.serial());
			boolean channelChanged = override.channel() > 0L
				? currentMode != LinkConnectionMode.CHANNEL || currentChannel != override.channel()
				: currentMode != LinkConnectionMode.SERIAL || currentChannel != 0L;
			if (channelChanged) {
				changedOverrides.add(override);
			}
		}
		return List.copyOf(changedOverrides);
	}

	private static String buildNodeKey(LinkNodeType nodeType, long serial) {
		return (nodeType == LinkNodeType.TRIGGER_SOURCE ? "triggerSource" : "core") + ":" + Math.max(0L, serial);
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
	 * 批量准备阶段结果。
	 */
	public record BatchPreparationResult(
		PreparedChannelBatchUpdate plan,
		List<LinkSetExecutionService.OperationFeedback> feedbacks
	) {
		public BatchPreparationResult {
			feedbacks = List.copyOf(feedbacks == null ? List.of() : feedbacks);
		}

		/**
		 * 返回失败结果。
		 */
		public static BatchPreparationResult failure(LinkSetExecutionService.OperationFeedback feedback) {
			return new BatchPreparationResult(null, List.of(feedback));
		}

		/**
		 * 返回成功结果。
		 */
		public static BatchPreparationResult success(
			PreparedChannelBatchUpdate plan,
			List<LinkSetExecutionService.OperationFeedback> feedbacks
		) {
			return new BatchPreparationResult(plan, feedbacks);
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
	 * 批量频道配置编辑计划。
	 */
	public record PreparedChannelBatchUpdate(
		ServerLevel level,
		ServerPlayer player,
		List<ChannelOverride> overrides,
		List<ChannelOverride> changedOverrides,
		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations,
		int totalCommandCost
	) {
		public PreparedChannelBatchUpdate {
			overrides = List.copyOf(overrides == null ? List.of() : overrides);
			changedOverrides = List.copyOf(changedOverrides == null ? List.of() : changedOverrides);
			preparedOperations = List.copyOf(preparedOperations == null ? List.of() : preparedOperations);
			totalCommandCost = Math.max(0, totalCommandCost);
		}

		/**
		 * @return 当前批量计划是否会改动任何频道配置或普通边
		 */
		public boolean hasChanges() {
			return !changedOverrides.isEmpty() || !preparedOperations.isEmpty();
		}

		/**
		 * @return 当前批量计划影响到的 triggerSource 写操作数
		 */
		public int changedTriggerSourceCount() {
			return preparedOperations.size();
		}

		/**
		 * @return 当前批量计划真实变更的节点数量
		 */
		public int changedChannelNodeCount() {
			return changedOverrides.size();
		}
	}

	/**
	 * 执行阶段结果。
	 */
	public record ApplyResult(int appliedOperationCount, int currentLinkedPeerCount) {}

	/**
	 * 批量执行阶段结果。
	 */
	public record BatchApplyResult(int appliedOperationCount, int changedChannelNodeCount) {}
}
