package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具服务端应用服务。
 * <p>
 * 该服务统一承接“站立右键把缓存应用到命中节点”的真实写入逻辑，
 * 严格保持 `triggerSource -> core` 单向写入约束。
 * </p>
 */
public final class QuickLinkApplyService {
	private QuickLinkApplyService() {
	}

	/**
	 * 将当前工具缓存应用到命中节点。
	 */
	public static QuickLinkOperationFeedback apply(ServerPlayer player, ServerLevel level, BlockPos blockPos, ItemStack stack) {
		if (player == null || level == null || blockPos == null || stack == null || stack.isEmpty()) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.mode.channel_future");
		}
		if (snapshot.serialCacheExpression().isBlank()) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (snapshot.serialCacheExpression().length() > maxInputLength) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength));
		}

		BlockEntity blockEntity = level.getBlockEntity(blockPos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		LinkNodeType targetNodeType = pairableNodeBlockEntity.getLinkNodeType();
		if (targetNodeType == null || pairableNodeBlockEntity.getSerial() <= 0L) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		LinkNodeType cacheType = snapshot.serialCacheType();
		if (cacheType == targetNodeType) {
			return QuickLinkOperationFeedback.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(targetNodeType)
			);
		}

		return cacheType == LinkNodeType.CORE
			? applyCachedCoresToTriggerSource(player, level, pairableNodeBlockEntity.getSerial(), snapshot.serialCacheExpression())
			: applyCachedTriggerSourcesToCore(player, level, pairableNodeBlockEntity.getSerial(), snapshot.serialCacheExpression());
	}

	/**
	 * 将缓存的 core 集合覆盖写入当前 triggerSource。
	 */
	private static QuickLinkOperationFeedback applyCachedCoresToTriggerSource(
		ServerPlayer player,
		ServerLevel level,
		long triggerSourceSerial,
		String rawExpression
	) {
		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, maxTargets);
		if (!parseResult.invalidEntries().isEmpty()) {
			return QuickLinkOperationFeedback.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.too_many_targets", Integer.toString(maxTargets));
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long coreSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.CORE, coreSerial)) {
				return QuickLinkOperationFeedback.failure("message.redstonelink.target_serial_unallocated", Long.toString(coreSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.CORE, coreSerial)) {
				return QuickLinkOperationFeedback.failure("message.redstonelink.target_serial_retired", Long.toString(coreSerial));
			}
			if (!RedstoneLinkConfig.general().allowOfflineTargetBinding() && savedData.findNode(LinkNodeType.CORE, coreSerial).isEmpty()) {
				return QuickLinkOperationFeedback.failure("message.redstonelink.offline_targets_blocked", Long.toString(coreSerial));
			}
		}

		Set<Long> nextTargets = new HashSet<>(parseResult.orderedTargets());
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		Set<Long> affectedTargets = new HashSet<>(previousTargets);
		affectedTargets.addAll(nextTargets);

		LinkWriteControlService.WriteDecision writeDecision = LinkWriteControlService.evaluateForPlayer(
			player,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			affectedTargets,
			nextTargets.size()
		);
		if (!writeDecision.allowed()) {
			return failureFromWriteDecision(writeDecision);
		}

		LinkSetExecutionService.ApplyResult applyResult = LinkSetExecutionService.applyPreparedReplace(
			LinkSetExecutionService.createPreparedReplaceOperation(
				level,
				player,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				LinkNodeType.CORE,
				previousTargets,
				nextTargets,
				List.of(),
				1
			)
		);
		return QuickLinkOperationFeedback.success(
			"message.redstonelink.quick_link.apply.done.trigger_source",
			Long.toString(triggerSourceSerial),
			Integer.toString(applyResult.currentTargetCount())
		);
	}

	/**
	 * 将缓存的 triggerSource 集合逐个覆盖为“仅连接当前 core”。
	 */
	private static QuickLinkOperationFeedback applyCachedTriggerSourcesToCore(
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String rawExpression
	) {
		int maxTargets = maxQuickLinkApplyTargetCount();
		SerialParseUtil.OrderedTargetParseResult parseResult = parseCachedTriggerSources(rawExpression);
		int writeControlSetSize = writeControlSetSizeForCachedTriggerSourcesToCore(parseResult.orderedTargets().size());
		if (!parseResult.invalidEntries().isEmpty()) {
			return QuickLinkOperationFeedback.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return QuickLinkOperationFeedback.failure(
				"message.redstonelink.quick_link.apply.too_many_sources",
				Integer.toString(maxTargets)
			);
		}
		if (parseResult.orderedTargets().isEmpty()) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return QuickLinkOperationFeedback.failure("message.redstonelink.source_serial_unallocated", Long.toString(triggerSourceSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return QuickLinkOperationFeedback.failure("message.redstonelink.source_serial_retired", Long.toString(triggerSourceSerial));
			}
		}

		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
			Set<Long> nextTargets = Set.of(coreSerial);
			Set<Long> affectedTargets = new HashSet<>(previousTargets);
			affectedTargets.add(coreSerial);
			LinkWriteControlService.WriteDecision writeDecision = LinkWriteControlService.evaluateForPlayer(
				player,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				affectedTargets,
				writeControlSetSize
			);
			if (!writeDecision.allowed()) {
				return failureFromWriteDecision(writeDecision);
			}
		}

		int appliedSourceCount = 0;
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
			Set<Long> nextTargets = Set.of(coreSerial);
			LinkSetExecutionService.applyPreparedReplace(
				LinkSetExecutionService.createPreparedReplaceOperation(
					level,
					player,
					LinkNodeType.TRIGGER_SOURCE,
					triggerSourceSerial,
					LinkNodeType.CORE,
					previousTargets,
					nextTargets,
					List.of(),
					1
				)
			);
			appliedSourceCount++;
		}

		return QuickLinkOperationFeedback.success(
			"message.redstonelink.quick_link.apply.done.core",
			Integer.toString(appliedSourceCount),
			Long.toString(coreSerial)
		);
	}

	/**
	 * 按现有 `set_links` 规则解析 quick-link 的 triggerSource 缓存。
	 */
	static SerialParseUtil.OrderedTargetParseResult parseCachedTriggerSources(String rawExpression) {
		return SerialParseUtil.parseTargetsOrdered(rawExpression, maxQuickLinkApplyTargetCount());
	}

	/**
	 * @return quick-link 应用时允许的最大目标数量
	 */
	static int maxQuickLinkApplyTargetCount() {
		return RedstoneLinkConfig.general().maxTargetsPerSetLinks();
	}

	/**
	 * @return quick-link 批量应用到 core 时的写控设置量
	 */
	static int writeControlSetSizeForCachedTriggerSourcesToCore(int cachedTriggerSourceCount) {
		return Math.max(0, cachedTriggerSourceCount);
	}

	/**
	 * 将写控判定映射为统一前端提示。
	 */
	static QuickLinkOperationFeedback failureFromWriteDecision(LinkWriteControlService.WriteDecision writeDecision) {
		return QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient");
	}
}
