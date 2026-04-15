package com.makomi.command.link;

import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkSavedDataChannelSupport;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * `core` 视角链接编辑服务。
 * <p>
 * 该服务只负责把“以 core 为观察中心”的编辑请求，翻译成若干条
 * `triggerSource -> core` 的正向覆盖写入计划，避免高层入口继续把
 * `CORE` 当成底层真实来源。
 * </p>
 */
public final class CoreLinkEditingService {
	private CoreLinkEditingService() {
	}

	/**
	 * 解析 `core` 视角输入的 triggerSource 序号表达式。
	 */
	public static ParseResult parseTriggerSourcesExpression(String rawTriggerSourceExpression) {
		String normalizedExpression = rawTriggerSourceExpression == null ? "" : rawTriggerSourceExpression.trim();
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			normalizedExpression,
			RedstoneLinkConfig.general().maxTargetsPerSetLinks()
		);
		return new ParseResult(
			List.copyOf(parseResult.orderedTargets()),
			parseResult.invalidEntries(),
			parseResult.duplicateEntries(),
			parseResult.exceedLimit()
		);
	}

	/**
	 * 读取本次 `core` 编辑涉及到的 triggerSource 当前目标集合。
	 */
	public static Map<Long, Set<Long>> loadCurrentTargetsByTriggerSource(
		LinkSavedData savedData,
		Set<Long> currentTriggerSources,
		List<Long> desiredTriggerSources
	) {
		LinkedHashMap<Long, Set<Long>> currentTargetsByTriggerSource = new LinkedHashMap<>();
		LinkedHashSet<Long> affectedTriggerSources = new LinkedHashSet<>();
		if (desiredTriggerSources != null) {
			affectedTriggerSources.addAll(desiredTriggerSources);
		}
		List<Long> sortedCurrentTriggerSources = new ArrayList<>();
		if (currentTriggerSources != null) {
			sortedCurrentTriggerSources.addAll(currentTriggerSources);
		}
		Collections.sort(sortedCurrentTriggerSources);
		affectedTriggerSources.addAll(sortedCurrentTriggerSources);
		for (long triggerSourceSerial : affectedTriggerSources) {
			currentTargetsByTriggerSource.put(
				triggerSourceSerial,
				resolveCurrentTargetsForCoreEditing(savedData, triggerSourceSerial)
			);
		}
		return currentTargetsByTriggerSource;
	}

	/**
	 * 将 `core` 视角输入转换成真正需要执行的 `triggerSource -> core` 正向目标集合。
	 * <p>
	 * 返回结果只包含目标集合实际发生变化的 triggerSource，避免无意义重复写入。
	 * </p>
	 */
	public static LinkedHashMap<Long, Set<Long>> buildChangedTargetsByTriggerSource(
		long coreSerial,
		Set<Long> currentTriggerSources,
		List<Long> desiredTriggerSources,
		Map<Long, Set<Long>> currentTargetsByTriggerSource
	) {
		LinkedHashMap<Long, Set<Long>> changedTargetsByTriggerSource = new LinkedHashMap<>();
		if (coreSerial <= 0L) {
			return changedTargetsByTriggerSource;
		}

		LinkedHashSet<Long> desiredTriggerSourceSet = new LinkedHashSet<>();
		if (desiredTriggerSources != null) {
			desiredTriggerSourceSet.addAll(desiredTriggerSources);
		}
		LinkedHashSet<Long> orderedAffectedTriggerSources = new LinkedHashSet<>(desiredTriggerSourceSet);
		List<Long> sortedCurrentTriggerSources = new ArrayList<>();
		if (currentTriggerSources != null) {
			sortedCurrentTriggerSources.addAll(currentTriggerSources);
		}
		Collections.sort(sortedCurrentTriggerSources);
		orderedAffectedTriggerSources.addAll(sortedCurrentTriggerSources);

		for (long triggerSourceSerial : orderedAffectedTriggerSources) {
			Set<Long> currentTargets = currentTargetsByTriggerSource == null
				? Set.of()
				: Set.copyOf(currentTargetsByTriggerSource.getOrDefault(triggerSourceSerial, Set.of()));
			LinkedHashSet<Long> nextTargets = new LinkedHashSet<>(currentTargets);
			if (desiredTriggerSourceSet.contains(triggerSourceSerial)) {
				nextTargets.add(coreSerial);
			} else {
				nextTargets.remove(coreSerial);
			}
			Set<Long> normalizedNextTargets = nextTargets.isEmpty() ? Set.of() : Set.copyOf(nextTargets);
			if (!normalizedNextTargets.equals(currentTargets)) {
				changedTargetsByTriggerSource.put(triggerSourceSerial, normalizedNextTargets);
			}
		}
		return changedTargetsByTriggerSource;
	}

	/**
	 * 为 `core` 视角编辑构造一组已完成校验的正向覆盖写入操作。
	 */
	public static PreparationResult prepareConfirmedReplace(
		ServerLevel level,
		ServerPlayer player,
		long coreSerial,
		List<Long> desiredTriggerSources,
		List<Long> duplicateEntries,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		if (level == null) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")
			);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		if (coreSerial <= 0L || !savedData.isSerialAllocated(LinkNodeType.CORE, coreSerial)) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure(
					"message.redstonelink.target_serial_unallocated",
					Long.toString(coreSerial)
				)
			);
		}
		if (savedData.isSerialRetired(LinkNodeType.CORE, coreSerial)) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure(
					"message.redstonelink.target_serial_retired",
					Long.toString(coreSerial)
				)
			);
		}

		List<Long> normalizedDesiredTriggerSources = desiredTriggerSources == null
			? List.of()
			: List.copyOf(new LinkedHashSet<>(desiredTriggerSources));
		List<Long> channelModeTriggerSources = normalizedDesiredTriggerSources
			.stream()
			.filter(triggerSourceSerial -> savedData.getConnectionMode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial) == LinkConnectionMode.CHANNEL)
			.toList();
		if (!channelModeTriggerSources.isEmpty()) {
			return PreparationResult.failure(
				LinkSetExecutionService.OperationFeedback.failure(
					"message.redstonelink.invalid_source_channel_mode",
					CommandTreeSupport.formatSerialList(channelModeTriggerSources)
				)
			);
		}
		List<LinkSetExecutionService.OperationFeedback> feedbacks = new ArrayList<>();
		if (duplicateEntries != null && !duplicateEntries.isEmpty()) {
			feedbacks.add(
				LinkSetExecutionService.OperationFeedback.success(
					"message.redstonelink.duplicate_targets_deduped",
					CommandTreeSupport.formatSerialCollection(duplicateEntries)
				)
			);
		}

		Set<Long> currentTriggerSources = savedData.getLinkedTriggerSourcesByCore(coreSerial);
		Map<Long, Set<Long>> currentTargetsByTriggerSource = loadCurrentTargetsByTriggerSource(
			savedData,
			currentTriggerSources,
			normalizedDesiredTriggerSources
		);
		LinkedHashMap<Long, Set<Long>> changedTargetsByTriggerSource = buildChangedTargetsByTriggerSource(
			coreSerial,
			currentTriggerSources,
			normalizedDesiredTriggerSources,
			currentTargetsByTriggerSource
		);

		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations = new ArrayList<>();
		int totalCommandCost = 0;
		for (Map.Entry<Long, Set<Long>> entry : changedTargetsByTriggerSource.entrySet()) {
			LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplaceResolvedTargets(
				level,
				player,
				LinkNodeType.TRIGGER_SOURCE,
				entry.getKey(),
				entry.getValue(),
				List.of(),
				hasLimitedBypassPermission,
				hasProtectedBypassPermission,
				false,
				false
			);
			if (!preparationResult.successful()) {
				List<LinkSetExecutionService.OperationFeedback> allFeedbacks = new ArrayList<>(feedbacks);
				allFeedbacks.addAll(preparationResult.feedbacks());
				return new PreparationResult(null, allFeedbacks);
			}
			preparedOperations.add(preparationResult.operation());
			totalCommandCost = saturatingAdd(totalCommandCost, preparationResult.operation().commandCost());
		}

		return PreparationResult.success(
			new PreparedReplacePlan(
				level,
				player,
				coreSerial,
				currentTriggerSources,
				normalizedDesiredTriggerSources,
				changedTargetsByTriggerSource,
				preparedOperations,
				totalCommandCost,
				savedData.getConnectionMode(LinkNodeType.CORE, coreSerial) == LinkConnectionMode.CHANNEL
			),
			feedbacks
		);
	}

	/**
	 * 执行一组已经准备完成的 `core` 视角写入计划。
	 */
	public static ApplyResult applyPreparedReplace(PreparedReplacePlan plan) {
		if (plan == null) {
			return new ApplyResult(0, 0);
		}
		if (plan.switchEditedCoreToSerialModeBeforeApply()) {
			LinkSavedDataChannelSupport.clearChannelConfig(LinkSavedData.get(plan.level()), LinkNodeType.CORE, plan.coreSerial());
		}
		int appliedOperationCount = 0;
		LinkCommandSupport.BatchLinkSnapshotSyncCollector batchSyncCollector = new LinkCommandSupport.BatchLinkSnapshotSyncCollector(plan.level());
		for (LinkSetExecutionService.PreparedReplaceOperation preparedOperation : plan.preparedOperations()) {
			LinkSetExecutionService.applyPreparedReplace(preparedOperation, batchSyncCollector);
			appliedOperationCount++;
		}
		batchSyncCollector.flush();
		return new ApplyResult(
			appliedOperationCount,
			LinkSavedData.get(plan.level()).getLinkedTriggerSourcesByCore(plan.coreSerial()).size()
		);
	}

	/**
	 * 饱和累加命令成本，避免极端批量场景下整数溢出。
	 */
	public static int saturatingAdd(int currentCost, int nextCost) {
		long resolved = (long) Math.max(0, currentCost) + Math.max(0, nextCost);
		return (int) Math.min(Integer.MAX_VALUE, resolved);
	}

	/**
	 * 为 core 视角编辑解析“当前应保留的基础目标集合”。
	 * <p>
	 * 对 serial 模式 triggerSource，会自动过滤掉 channel 模式 core；
	 * 对 channel 模式 triggerSource，则按当前频道配置回推其应有目标集合。
	 * </p>
	 */
	private static Set<Long> resolveCurrentTargetsForCoreEditing(LinkSavedData savedData, long triggerSourceSerial) {
		if (savedData == null || triggerSourceSerial <= 0L) {
			return Set.of();
		}
		return LinkSavedDataChannelSupport.resolveDesiredTargetsForTriggerSourceWithOverride(
			savedData,
			triggerSourceSerial,
			null,
			0L,
			null,
			0L
		);
	}

	/**
	 * `core` 视角的输入解析结果。
	 */
	public record ParseResult(
		List<Long> orderedTriggerSources,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {
		public ParseResult {
			orderedTriggerSources = List.copyOf(orderedTriggerSources == null ? List.of() : orderedTriggerSources);
			invalidEntries = List.copyOf(invalidEntries == null ? List.of() : invalidEntries);
			duplicateEntries = List.copyOf(duplicateEntries == null ? List.of() : duplicateEntries);
		}
	}

	/**
	 * 准备阶段结果。
	 */
	public record PreparationResult(PreparedReplacePlan plan, List<LinkSetExecutionService.OperationFeedback> feedbacks) {
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
			PreparedReplacePlan plan,
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
	 * `core` 视角编辑转换出的正向写入计划。
	 */
	public record PreparedReplacePlan(
		ServerLevel level,
		ServerPlayer player,
		long coreSerial,
		Set<Long> currentTriggerSources,
		List<Long> desiredTriggerSources,
		Map<Long, Set<Long>> changedTargetsByTriggerSource,
		List<LinkSetExecutionService.PreparedReplaceOperation> preparedOperations,
		int totalCommandCost,
		boolean switchEditedCoreToSerialModeBeforeApply
	) {
		public PreparedReplacePlan {
			currentTriggerSources = Set.copyOf(currentTriggerSources == null ? Set.of() : currentTriggerSources);
			desiredTriggerSources = List.copyOf(desiredTriggerSources == null ? List.of() : desiredTriggerSources);

			LinkedHashMap<Long, Set<Long>> normalizedChangedTargetsByTriggerSource = new LinkedHashMap<>();
			if (changedTargetsByTriggerSource != null) {
				for (Map.Entry<Long, Set<Long>> entry : changedTargetsByTriggerSource.entrySet()) {
					if (entry != null && entry.getKey() != null) {
						normalizedChangedTargetsByTriggerSource.put(entry.getKey(), Set.copyOf(entry.getValue()));
					}
				}
			}
			changedTargetsByTriggerSource = Collections.unmodifiableMap(normalizedChangedTargetsByTriggerSource);
			preparedOperations = List.copyOf(preparedOperations == null ? List.of() : preparedOperations);
			totalCommandCost = Math.max(0, totalCommandCost);
		}

		/**
		 * 当前 `core` 已关联的 triggerSource 数量。
		 */
		public int currentTriggerSourceCount() {
			return currentTriggerSources.size();
		}

		/**
		 * 本次希望保留的 triggerSource 数量。
		 */
		public int desiredTriggerSourceCount() {
			return desiredTriggerSources.size();
		}

		/**
		 * 本次真正需要改写的 triggerSource 数量。
		 */
		public int changedTriggerSourceCount() {
			return preparedOperations.size();
		}

		/**
		 * 当前是否存在真实需要落库的变更。
		 */
		public boolean hasChanges() {
			return !preparedOperations.isEmpty();
		}
	}

	/**
	 * 执行阶段结果。
	 */
	public record ApplyResult(int appliedOperationCount, int currentTriggerSourceCount) {
		public ApplyResult {
			appliedOperationCount = Math.max(0, appliedOperationCount);
			currentTriggerSourceCount = Math.max(0, currentTriggerSourceCount);
		}
	}
}
