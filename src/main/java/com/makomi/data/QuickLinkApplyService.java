package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.command.link.LinkCommandSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
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

		BlockEntity blockEntity = level.getBlockEntity(blockPos);
		if (blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			return applyToFilterFromCache(player, filterBlockEntity, snapshot.serialCacheType(), snapshot.serialCacheExpression()).feedback();
		}
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

		return applyFromCache(
			player.createCommandSourceStack(),
			player,
			level,
			targetNodeType,
			pairableNodeBlockEntity.getSerial(),
			cacheType,
			snapshot.serialCacheExpression()
		)
			.feedback();
	}

	/**
	 * 将当前缓存应用到命中过滤器。
	 * <p>
	 * quick-link 对过滤器的应用只覆盖 `serialExpression`，其余过滤器配置保持不变；
	 * 同时权限口径与过滤器编辑界面保存保持一致，统一要求命令权限。
	 * </p>
	 */
	public static ApplyFromCacheResult applyToFilterFromCache(
		ServerPlayer player,
		AbstractLinkFilterBlockEntity filterBlockEntity,
		LinkNodeType cacheType,
		String serialCacheExpression
	) {
		if (player == null || filterBlockEntity == null || filterBlockEntity.filterKind() == null) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
			return ApplyFromCacheResult.failure("message.redstonelink.permission.insufficient");
		}
		if (!isCacheTypeCompatibleWithFilter(cacheType, filterBlockEntity.filterKind())) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(expectedCacheTypeForFilter(filterBlockEntity.filterKind()))
			);
		}

		String normalizedExpression = serialCacheExpression == null ? "" : serialCacheExpression.trim();
		if (normalizedExpression.isBlank()) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}
		if (normalizedExpression.length() > RedstoneLinkConfig.command().linkSetMaxInputLength()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.link_filter.input_too_long",
				Integer.toString(RedstoneLinkConfig.command().linkSetMaxInputLength())
			);
		}

		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			normalizedExpression,
			RedstoneLinkConfig.general().maxTargetsPerSetLinks()
		);
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.pairing.invalid_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.link_filter.too_many_serials",
				Integer.toString(RedstoneLinkConfig.general().maxTargetsPerSetLinks())
			);
		}

		filterBlockEntity.applySnapshot(buildFilterSnapshotForAppliedCache(filterBlockEntity.snapshot(), parseResult.orderedTargets()));
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				filterApplySuccessMessageKey(filterBlockEntity.filterKind()),
				Integer.toString(parseResult.orderedTargets().size())
			),
			0,
			parseResult.orderedTargets().size()
		);
	}

	/**
	 * 按显式缓存参数执行一次 quick-link 应用。
	 */
	static ApplyFromCacheResult applyFromCache(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		LinkNodeType cacheType,
		String serialCacheExpression
	) {
		if (level == null || targetNodeType == null || targetNodeSerial <= 0L) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		if (cacheType == null) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		String normalizedExpression = serialCacheExpression == null ? "" : serialCacheExpression.trim();
		if (normalizedExpression.isBlank()) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}
		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (normalizedExpression.length() > maxInputLength) {
			return ApplyFromCacheResult.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength));
		}
		if (cacheType == targetNodeType) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(targetNodeType)
			);
		}

		return cacheType == LinkNodeType.CORE
			? applyCachedCoresToTriggerSource(commandSource, player, level, targetNodeSerial, normalizedExpression)
			: applyCachedTriggerSourcesToCore(commandSource, player, level, targetNodeSerial, normalizedExpression);
	}

	/**
	 * 将缓存的 core 集合覆盖写入当前 triggerSource。
	 */
	private static ApplyFromCacheResult applyCachedCoresToTriggerSource(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long triggerSourceSerial,
		String rawExpression
	) {
		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, maxTargets);
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyFromCacheResult.failure("message.redstonelink.too_many_targets", Integer.toString(maxTargets));
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long coreSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.CORE, coreSerial)) {
				return ApplyFromCacheResult.failure("message.redstonelink.target_serial_unallocated", Long.toString(coreSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.CORE, coreSerial)) {
				return ApplyFromCacheResult.failure("message.redstonelink.target_serial_retired", Long.toString(coreSerial));
			}
			if (!RedstoneLinkConfig.general().allowOfflineTargetBinding() && savedData.findNode(LinkNodeType.CORE, coreSerial).isEmpty()) {
				return ApplyFromCacheResult.failure("message.redstonelink.offline_targets_blocked", Long.toString(coreSerial));
			}
		}

		Set<Long> nextTargets = new HashSet<>(parseResult.orderedTargets());
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(triggerSourceSerial));
		Set<Long> affectedTargets = new HashSet<>(previousTargets);
		affectedTargets.addAll(nextTargets);

		LinkWriteControlService.WriteDecision writeDecision = resolveWriteDecision(
			commandSource,
			level,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			affectedTargets,
			nextTargets.size()
		);
		if (!writeDecision.allowed()) {
			return new ApplyFromCacheResult(failureFromWriteDecision(writeDecision), 0, previousTargets.size());
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
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.apply.done.trigger_source",
				Long.toString(triggerSourceSerial),
				Integer.toString(applyResult.currentTargetCount())
			),
			1,
			applyResult.currentTargetCount()
		);
	}

	/**
	 * 将缓存的 triggerSource 集合逐个覆盖为“仅连接当前 core”。
	 */
	private static ApplyFromCacheResult applyCachedTriggerSourcesToCore(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String rawExpression
	) {
		int maxTargets = maxQuickLinkApplyTargetCount();
		SerialParseUtil.OrderedTargetParseResult parseResult = parseCachedTriggerSources(rawExpression);
		int writeControlSetSize = writeControlSetSizeForCachedTriggerSourcesToCore(parseResult.orderedTargets().size());
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.quick_link.apply.too_many_sources",
				Integer.toString(maxTargets)
			);
		}
		if (parseResult.orderedTargets().isEmpty()) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return ApplyFromCacheResult.failure("message.redstonelink.source_serial_unallocated", Long.toString(triggerSourceSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return ApplyFromCacheResult.failure("message.redstonelink.source_serial_retired", Long.toString(triggerSourceSerial));
			}
		}

		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(triggerSourceSerial));
			Set<Long> affectedTargets = new HashSet<>(previousTargets);
			affectedTargets.add(coreSerial);
			LinkWriteControlService.WriteDecision writeDecision = resolveWriteDecision(
				commandSource,
				level,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				affectedTargets,
				writeControlSetSize
			);
			if (!writeDecision.allowed()) {
				return new ApplyFromCacheResult(failureFromWriteDecision(writeDecision), 0, 0);
			}
		}

		int appliedSourceCount = 0;
		LinkCommandSupport.BatchLinkSnapshotSyncCollector batchSyncCollector = new LinkCommandSupport.BatchLinkSnapshotSyncCollector(level);
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(triggerSourceSerial));
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
				),
				batchSyncCollector
			);
			appliedSourceCount++;
		}
		batchSyncCollector.flush();

		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.apply.done.core",
				Integer.toString(appliedSourceCount),
				Long.toString(coreSerial)
			),
			appliedSourceCount,
			appliedSourceCount > 0 ? 1 : 0
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
	 * 判断当前缓存类型是否能应用到指定过滤器。
	 */
	static boolean isCacheTypeCompatibleWithFilter(LinkNodeType cacheType, LinkFilterKind filterKind) {
		return cacheType != null && cacheType == expectedCacheTypeForFilter(filterKind);
	}

	/**
	 * 根据过滤器种类解析 quick-link 允许写入的缓存类型。
	 */
	static LinkNodeType expectedCacheTypeForFilter(LinkFilterKind filterKind) {
		return filterKind == null ? null : filterKind.servicedNodeType();
	}

	/**
	 * 基于当前过滤器配置，仅替换序号表达式并保留其余运行参数。
	 */
	static LinkFilterConfigSnapshot buildFilterSnapshotForAppliedCache(
		LinkFilterConfigSnapshot currentSnapshot,
		List<Long> orderedSerials
	) {
		LinkFilterConfigSnapshot normalizedSnapshot = currentSnapshot == null
			? new LinkFilterConfigSnapshot("", null, null, 15, null)
			: currentSnapshot;
		return new LinkFilterConfigSnapshot(
			buildFilterSerialExpression(orderedSerials),
			normalizedSnapshot.nodeSetMode(),
			normalizedSnapshot.signalThresholdSource(),
			normalizedSnapshot.fixedSignalThreshold(),
			normalizedSnapshot.signalMode()
		);
	}

	/**
	 * 解析过滤器应用成功反馈的翻译键。
	 */
	static String filterApplySuccessMessageKey(LinkFilterKind filterKind) {
		return filterKind == LinkFilterKind.RECEIVE
			? "message.redstonelink.quick_link.apply.done.receive_filter"
			: "message.redstonelink.quick_link.apply.done.send_filter";
	}

	/**
	 * 基于命令源权限解析写控判定。
	 */
	private static LinkWriteControlService.WriteDecision resolveWriteDecision(
		CommandSourceStack commandSource,
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> affectedTargets,
		int setSize
	) {
		boolean hasLimitedBypassPermission = commandSource != null
			&& commandSource.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel());
		boolean hasProtectedBypassPermission = commandSource != null
			&& commandSource.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel());
		return LinkWriteControlService.evaluate(
			level,
			sourceType,
			sourceSerial,
			affectedTargets,
			setSize,
			hasLimitedBypassPermission,
			hasProtectedBypassPermission
		);
	}

	/**
	 * 将写控判定映射为统一前端提示。
	 */
	static QuickLinkOperationFeedback failureFromWriteDecision(LinkWriteControlService.WriteDecision writeDecision) {
		return QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient");
	}

	/**
	 * 将 quick-link 缓存中的有序序号集合还原为过滤器可持久化的 `/` 分段表达式。
	 */
	private static String buildFilterSerialExpression(List<Long> orderedSerials) {
		if (orderedSerials == null || orderedSerials.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (Long orderedSerial : orderedSerials) {
			if (orderedSerial == null || orderedSerial <= 0L) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append('/');
			}
			builder.append(orderedSerial);
		}
		return builder.toString();
	}

	/**
	 * quick-link 显式缓存应用结果。
	 */
	public record ApplyFromCacheResult(
		QuickLinkOperationFeedback feedback,
		int affectedSourceCount,
		int currentTargetCount
	) {
		public ApplyFromCacheResult {
			affectedSourceCount = Math.max(0, affectedSourceCount);
			currentTargetCount = Math.max(0, currentTargetCount);
		}

		static ApplyFromCacheResult failure(String messageKey, String... messageArgs) {
			return new ApplyFromCacheResult(QuickLinkOperationFeedback.failure(messageKey, messageArgs), 0, 0);
		}
	}
}
