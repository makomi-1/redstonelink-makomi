package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.command.link.CoreLinkEditingService;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
		if (
			snapshot.mode() == QuickLinkToolData.Mode.SERIAL &&
			!allowsEmptySerialCacheApply(snapshot.applyEditMode()) &&
			snapshot.serialCacheExpression().isBlank()
		) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		BlockEntity blockEntity = level.getBlockEntity(blockPos);
		if (blockEntity instanceof LinkRepeaterBlockEntity repeaterBlockEntity) {
			return applyToRepeaterFromCache(
				player,
				repeaterBlockEntity,
				snapshot.mode(),
				snapshot.serialCacheType(),
				snapshot.serialCacheExpression(),
				snapshot.channelCache(),
				snapshot.applyEditMode()
			).feedback();
		}
		if (blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			return applyToFilterFromCache(
				player,
				filterBlockEntity,
				snapshot.mode(),
				snapshot.serialCacheType(),
				snapshot.serialCacheExpression(),
				snapshot.channelCache(),
				snapshot.applyEditMode()
			).feedback();
		}
		if (blockEntity instanceof LinkChunkActivatorBlockEntity chunkActivatorBlockEntity) {
			return applyToChunkActivatorFromCache(
				player,
				chunkActivatorBlockEntity,
				snapshot.mode(),
				snapshot.serialCacheType(),
				snapshot.serialCacheExpression(),
				snapshot.channelCache(),
				snapshot.applyEditMode()
			).feedback();
		}
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		LinkNodeType targetNodeType = pairableNodeBlockEntity.getLinkNodeType();
		if (targetNodeType == null || pairableNodeBlockEntity.getSerial() <= 0L) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			return applyChannelFromCache(
				player.createCommandSourceStack(),
				player,
				level,
				targetNodeType,
				pairableNodeBlockEntity.getSerial(),
				snapshot.channelCache()
			)
				.feedback();
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
			snapshot.serialCacheExpression(),
			snapshot.applyEditMode()
		)
			.feedback();
	}

	/**
	 * 将当前缓存应用到命中过滤器。
	 * <p>
	 * quick-link 对过滤器的应用只修改 `serialExpression`，其余过滤器配置保持不变；
	 * 同时权限口径与过滤器编辑界面保存保持一致，统一要求命令权限。
	 * </p>
	 */
	public static ApplyFromCacheResult applyToFilterFromCache(
		ServerPlayer player,
		AbstractLinkFilterBlockEntity filterBlockEntity,
		QuickLinkToolData.Mode mode,
		LinkNodeType cacheType,
		String serialCacheExpression,
		String channelCache,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		if (player == null || filterBlockEntity == null || filterBlockEntity.filterKind() == null) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
			return ApplyFromCacheResult.failure("message.redstonelink.permission.insufficient");
		}
		QuickLinkToolData.Mode resolvedMode = mode == null ? QuickLinkToolData.Mode.SERIAL : mode;
		if (resolvedMode == QuickLinkToolData.Mode.SERIAL && !isCacheTypeCompatibleWithFilter(cacheType, filterBlockEntity.filterKind())) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(expectedCacheTypeForFilter(filterBlockEntity.filterKind()))
			);
		}
		if (resolvedMode == QuickLinkToolData.Mode.CHANNEL) {
			long parsedChannel = new QuickLinkToolData.ChannelCacheValue(channelCache).parseChannelOrZero();
			filterBlockEntity.applySnapshot(buildChannelFilterSnapshotForAppliedCache(filterBlockEntity.snapshot(), parsedChannel));
			return new ApplyFromCacheResult(
				QuickLinkOperationFeedback.success(
					filterChannelApplySuccessMessageKey(filterBlockEntity.filterKind()),
					Long.toString(parsedChannel)
				),
				0,
				1
			);
		}

		String normalizedExpression = serialCacheExpression == null ? "" : serialCacheExpression.trim();
		if (!allowsEmptySerialCacheApply(applyEditMode) && normalizedExpression.isBlank()) {
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

		List<Long> nextOrderedSerials = buildNextFilterOrderedSerials(
			parseFilterOrderedSerials(filterBlockEntity.snapshot().serialExpression()),
			parseResult.orderedTargets(),
			applyEditMode
		);
		filterBlockEntity.applySnapshot(buildFilterSnapshotForAppliedCache(filterBlockEntity.snapshot(), nextOrderedSerials));
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				filterApplySuccessMessageKey(filterBlockEntity.filterKind()),
				Integer.toString(nextOrderedSerials.size())
			),
			0,
			nextOrderedSerials.size()
		);
	}

	/**
	 * 将当前缓存应用到命中区块激活器。
	 * <p>
	 * quick-link 对区块激活器只允许写当前生效服务对象对应的节点集，
	 * 并保持另一套 `triggerSource/core` 配置、别名与激活模式不变。
	 * </p>
	 */
	public static ApplyFromCacheResult applyToChunkActivatorFromCache(
		ServerPlayer player,
		LinkChunkActivatorBlockEntity chunkActivatorBlockEntity,
		QuickLinkToolData.Mode mode,
		LinkNodeType cacheType,
		String serialCacheExpression,
		String channelCache,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		if (player == null || chunkActivatorBlockEntity == null || !(chunkActivatorBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
			return ApplyFromCacheResult.failure("message.redstonelink.permission.insufficient");
		}
		QuickLinkToolData.Mode resolvedMode = mode == null ? QuickLinkToolData.Mode.SERIAL : mode;
		if (resolvedMode != QuickLinkToolData.Mode.SERIAL) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.chunk_activator_requires_serial");
		}
		if (!isCacheTypeCompatibleWithChunkActivator(cacheType, chunkActivatorBlockEntity.activeType())) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(ChunkActivatorConfigStateSnapshot.normalizeType(chunkActivatorBlockEntity.activeType()))
			);
		}

		String normalizedExpression = serialCacheExpression == null ? "" : serialCacheExpression.trim();
		if (!allowsEmptySerialCacheApply(applyEditMode) && normalizedExpression.isBlank()) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}
		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (normalizedExpression.length() > maxInputLength) {
			return ApplyFromCacheResult.failure("message.redstonelink.chunk_activator.input_too_long", Integer.toString(maxInputLength));
		}

		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			normalizedExpression,
			PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
		);
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.pairing.invalid_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.chunk_activator.too_many_serials",
				Integer.toString(PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE)
			);
		}

		List<Long> nextOrderedSerials = buildNextChunkActivatorOrderedSerials(
			chunkActivatorBlockEntity.snapshot(),
			parseResult.orderedTargets(),
			applyEditMode
		);
		if (nextOrderedSerials.size() > PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE) {
			return ApplyFromCacheResult.failure(
				"message.redstonelink.chunk_activator.too_many_serials",
				Integer.toString(PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE)
			);
		}

		ChunkActivatorConfigStateSnapshot nextSnapshot = buildChunkActivatorSnapshotForAppliedCache(
			chunkActivatorBlockEntity.snapshot(),
			nextOrderedSerials
		);
		if (nextSnapshot.activeConfig().serialExpression().length() > maxInputLength) {
			return ApplyFromCacheResult.failure("message.redstonelink.chunk_activator.input_too_long", Integer.toString(maxInputLength));
		}
		QuickLinkOperationFeedback residentCapacityFeedback = validateChunkActivatorResidentCapacity(
			serverLevel,
			chunkActivatorBlockEntity,
			nextSnapshot
		);
		if (residentCapacityFeedback != null) {
			return new ApplyFromCacheResult(residentCapacityFeedback, 0, 0);
		}

		chunkActivatorBlockEntity.applySnapshot(nextSnapshot);
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.apply.done.chunk_activator",
				Integer.toString(nextOrderedSerials.size()),
				LinkNodeSemantics.toSemanticName(nextSnapshot.activeType())
			),
			0,
			nextOrderedSerials.size()
		);
	}

	/**
	 * 将当前缓存应用到命中转发器。
	 * <p>
	 * 转发器编辑器展示的是图真值，因此 quick-link 不能只改本地表达式缓存，
	 * 而必须映射到真实 `triggerSource -> core` 图写入：
	 * `triggerSource` 缓存对应输入侧（按 `core` 目标提交），
	 * `core` 缓存对应输出侧（按 `triggerSource` 目标提交）。
	 * </p>
	 */
	public static ApplyFromCacheResult applyToRepeaterFromCache(
		ServerPlayer player,
		LinkRepeaterBlockEntity repeaterBlockEntity,
		QuickLinkToolData.Mode mode,
		LinkNodeType cacheType,
		String serialCacheExpression,
		String channelCache,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		if (player == null || repeaterBlockEntity == null || !(repeaterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		QuickLinkToolData.Mode resolvedMode = mode == null ? QuickLinkToolData.Mode.SERIAL : mode;
		if (resolvedMode != QuickLinkToolData.Mode.SERIAL) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.repeater_requires_serial");
		}
		LinkNodeType repeaterTargetType = resolveRepeaterTargetType(cacheType);
		if (repeaterTargetType == null) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		ApplyFromCacheResult applyResult = applyFromCache(
			player.createCommandSourceStack(),
			player,
			serverLevel,
			repeaterTargetType,
			repeaterBlockEntity.getSerial(),
			cacheType,
			serialCacheExpression,
			applyEditMode
		);
		if (!applyResult.feedback().success()) {
			return applyResult;
		}
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				repeaterApplySuccessMessageKey(cacheType),
				Integer.toString(applyResult.currentTargetCount())
			),
			applyResult.affectedSourceCount(),
			applyResult.currentTargetCount()
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
		return applyFromCache(
			commandSource,
			player,
			level,
			targetNodeType,
			targetNodeSerial,
			cacheType,
			serialCacheExpression,
			QuickLinkToolData.ApplyEditMode.REPLACE
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
		String serialCacheExpression,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		if (level == null || targetNodeType == null || targetNodeSerial <= 0L) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		if (cacheType == null) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		String normalizedExpression = serialCacheExpression == null ? "" : serialCacheExpression.trim();
		if (!allowsEmptySerialCacheApply(applyEditMode) && normalizedExpression.isBlank()) {
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
			? applyCachedCoresToTriggerSource(commandSource, player, level, targetNodeSerial, normalizedExpression, applyEditMode)
			: applyCachedTriggerSourcesToCore(commandSource, player, level, targetNodeSerial, normalizedExpression, applyEditMode);
	}

	/**
	 * 将频道缓存应用到命中节点。
	 */
	static ApplyFromCacheResult applyChannelFromCache(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		String channelCache
	) {
		if (level == null || targetNodeType == null || targetNodeSerial <= 0L) {
			return ApplyFromCacheResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		long channel = new QuickLinkToolData.ChannelCacheValue(channelCache).parseChannelOrZero();

		LinkChannelEditingService.PreparationResult preparationResult = LinkChannelEditingService.prepareConfirmedSetChannel(
			level,
			player,
			targetNodeType,
			targetNodeSerial,
			channel,
			hasLimitedBypassPermission(commandSource),
			hasProtectedBypassPermission(commandSource)
		);
		if (!preparationResult.successful()) {
			return new ApplyFromCacheResult(toQuickLinkFeedback(preparationResult.feedbacks().get(0)), 0, 0);
		}

		LinkChannelEditingService.ApplyResult applyResult = LinkChannelEditingService.applyPreparedSetChannel(preparationResult.plan());
		if (channel > 0L) {
			String messageKey = targetNodeType == LinkNodeType.TRIGGER_SOURCE
				? "message.redstonelink.quick_link.apply.done.trigger_source_channel"
				: "message.redstonelink.quick_link.apply.done.core_channel";
			return new ApplyFromCacheResult(
				QuickLinkOperationFeedback.success(
					messageKey,
					Long.toString(targetNodeSerial),
					Long.toString(channel),
					Integer.toString(applyResult.currentLinkedPeerCount())
				),
				applyResult.appliedOperationCount(),
				applyResult.currentLinkedPeerCount()
			);
		}

		String clearedMessageKey = targetNodeType == LinkNodeType.TRIGGER_SOURCE
			? "message.redstonelink.quick_link.apply.done.trigger_source_serial_mode"
			: "message.redstonelink.quick_link.apply.done.core_serial_mode";
		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				clearedMessageKey,
				Long.toString(targetNodeSerial),
				Integer.toString(applyResult.currentLinkedPeerCount())
			),
			applyResult.appliedOperationCount(),
			applyResult.currentLinkedPeerCount()
		);
	}

	/**
	 * 将缓存的 core 集合覆盖写入当前 triggerSource。
	 */
	private static ApplyFromCacheResult applyCachedCoresToTriggerSource(
		CommandSourceStack commandSource,
		ServerPlayer player,
		ServerLevel level,
		long triggerSourceSerial,
		String rawExpression,
		QuickLinkToolData.ApplyEditMode applyEditMode
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
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(triggerSourceSerial));
		Set<Long> nextTargets = buildNextSourceTargets(previousTargets, parseResult.orderedTargets(), applyEditMode);

		LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplace(
			level,
			player,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			nextTargets,
			hasLimitedBypassPermission(commandSource),
			hasProtectedBypassPermission(commandSource)
		);
		if (!preparationResult.successful()) {
			return new ApplyFromCacheResult(toQuickLinkFeedback(preparationResult.feedbacks().get(0)), 0, previousTargets.size());
		}

		LinkSetExecutionService.ApplyResult applyResult = LinkSetExecutionService.applyPreparedReplace(
			preparationResult.operation()
		);
		syncRepeaterDisplaysIfNeeded(level, triggerSourceSerial);
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
		String rawExpression,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		int maxTargets = maxQuickLinkApplyTargetCount();
		SerialParseUtil.OrderedTargetParseResult parseResult = parseCachedTriggerSources(rawExpression);
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

		LinkSavedData savedData = LinkSavedData.get(level);
		Set<Long> currentTriggerSources = savedData.getLinkedTriggerSourcesByCore(coreSerial);
		List<Long> desiredTriggerSources = buildDesiredTriggerSourcesForCoreApply(
			currentTriggerSources,
			parseResult.orderedTargets(),
			applyEditMode
		);

		CoreLinkEditingService.PreparationResult preparationResult = CoreLinkEditingService.prepareConfirmedReplace(
			level,
			player,
			coreSerial,
			desiredTriggerSources,
			List.copyOf(parseResult.duplicateEntries()),
			hasLimitedBypassPermission(commandSource),
			hasProtectedBypassPermission(commandSource)
		);
		if (!preparationResult.successful()) {
			return new ApplyFromCacheResult(toQuickLinkFeedback(preparationResult.feedbacks().get(0)), 0, currentTriggerSources.size());
		}

		CoreLinkEditingService.ApplyResult applyResult = CoreLinkEditingService.applyPreparedReplace(preparationResult.plan());
		Set<Long> repeaterSerialsToSync = new LinkedHashSet<>();
		repeaterSerialsToSync.add(coreSerial);
		repeaterSerialsToSync.addAll(currentTriggerSources);
		repeaterSerialsToSync.addAll(desiredTriggerSources);
		syncRepeaterDisplaysIfNeeded(level, repeaterSerialsToSync);

		return new ApplyFromCacheResult(
			QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.apply.done.core",
				Long.toString(coreSerial),
				Integer.toString(applyResult.currentTriggerSourceCount())
			),
			preparationResult.plan().changedTriggerSourceCount(),
			applyResult.currentTriggerSourceCount()
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
	 * 判断当前缓存类型是否能应用到区块激活器当前生效服务对象。
	 */
	static boolean isCacheTypeCompatibleWithChunkActivator(LinkNodeType cacheType, LinkNodeType activatorActiveType) {
		return cacheType != null && cacheType == ChunkActivatorConfigStateSnapshot.normalizeType(activatorActiveType);
	}

	/**
	 * 判断当前缓存类型是否能应用到转发器。
	 * <p>
	 * `triggerSource` 写输入配置，`core` 写输出配置。
	 * </p>
	 */
	static boolean isCacheTypeCompatibleWithRepeater(LinkNodeType cacheType) {
		return cacheType == LinkNodeType.TRIGGER_SOURCE || cacheType == LinkNodeType.CORE;
	}

	/**
	 * 将缓存类型映射为转发器 quick-link 应提交到的真实目标身份。
	 */
	static LinkNodeType resolveRepeaterTargetType(LinkNodeType cacheType) {
		if (cacheType == LinkNodeType.TRIGGER_SOURCE) {
			return LinkNodeType.CORE;
		}
		if (cacheType == LinkNodeType.CORE) {
			return LinkNodeType.TRIGGER_SOURCE;
		}
		return null;
	}

	/**
	 * 图真值变更后，若命中转发器统一序号，则同步其在线方块实体与物品摘要。
	 */
	private static void syncRepeaterDisplaysIfNeeded(ServerLevel level, long serial) {
		if (level == null || serial <= 0L || !LinkSavedData.get(level).isRepeaterSerial(serial)) {
			return;
		}
		RepeaterGraphSnapshotSupport.syncOnlineRepeaterDisplays(level.getServer(), serial);
	}

	/**
	 * 批量同步一组可能受影响的转发器统一序号。
	 */
	private static void syncRepeaterDisplaysIfNeeded(ServerLevel level, Set<Long> serials) {
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
			LinkFilterTargetMode.SERIAL,
			0L,
			normalizedSnapshot.nodeSetMode(),
			normalizedSnapshot.signalThresholdSource(),
			normalizedSnapshot.fixedSignalThreshold(),
			normalizedSnapshot.signalMode()
		);
	}

	/**
	 * 基于当前过滤器配置，仅替换频道值并切到频道模式。
	 */
	static LinkFilterConfigSnapshot buildChannelFilterSnapshotForAppliedCache(
		LinkFilterConfigSnapshot currentSnapshot,
		long channel
	) {
		LinkFilterConfigSnapshot normalizedSnapshot = currentSnapshot == null
			? new LinkFilterConfigSnapshot("", null, null, 15, null)
			: currentSnapshot;
		return new LinkFilterConfigSnapshot(
			"",
			LinkFilterTargetMode.CHANNEL,
			Math.max(0L, channel),
			normalizedSnapshot.nodeSetMode(),
			normalizedSnapshot.signalThresholdSource(),
			normalizedSnapshot.fixedSignalThreshold(),
			normalizedSnapshot.signalMode()
		);
	}

	/**
	 * 计算区块激活器当前生效节点集在 quick-link 三态应用后的结果。
	 */
	static List<Long> buildNextChunkActivatorOrderedSerials(
		ChunkActivatorConfigStateSnapshot currentSnapshot,
		List<Long> cachedOrderedSerials,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		ChunkActivatorConfigStateSnapshot normalizedSnapshot = currentSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(LinkNodeType.TRIGGER_SOURCE, null, null)
			: currentSnapshot;
		return buildNextFilterOrderedSerials(
			parseFilterOrderedSerials(normalizedSnapshot.activeConfig().serialExpression()),
			cachedOrderedSerials,
			applyEditMode
		);
	}

	/**
	 * 基于当前区块激活器快照，仅覆盖当前生效服务对象对应的节点集。
	 */
	static ChunkActivatorConfigStateSnapshot buildChunkActivatorSnapshotForAppliedCache(
		ChunkActivatorConfigStateSnapshot currentSnapshot,
		List<Long> nextOrderedSerials
	) {
		ChunkActivatorConfigStateSnapshot normalizedSnapshot = currentSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(LinkNodeType.TRIGGER_SOURCE, null, null)
			: currentSnapshot;
		LinkNodeType activeType = normalizedSnapshot.activeType();
		ChunkActivatorConfigSnapshot activeConfig = normalizedSnapshot.activeConfig();
		return normalizedSnapshot.withConfig(
			activeType,
			new ChunkActivatorConfigSnapshot(buildFilterSerialExpression(nextOrderedSerials), activeConfig.mode())
		);
	}

	/**
	 * 计算转发器指定配置侧在 quick-link 三态应用后的结果。
	 */
	static List<Long> buildNextRepeaterOrderedSerials(
		RepeaterConfigSnapshot currentSnapshot,
		LinkNodeType cacheType,
		List<Long> cachedOrderedSerials,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		RepeaterConfigSnapshot normalizedSnapshot = currentSnapshot == null ? RepeaterConfigSnapshot.empty() : currentSnapshot;
		String currentExpression = cacheType == LinkNodeType.TRIGGER_SOURCE
			? normalizedSnapshot.inputSerialExpression()
			: normalizedSnapshot.outputSerialExpression();
		return buildNextFilterOrderedSerials(parseFilterOrderedSerials(currentExpression), cachedOrderedSerials, applyEditMode);
	}

	/**
	 * 基于当前转发器配置，仅覆盖命中缓存类型对应的一侧表达式，并保留延迟与另一侧配置。
	 */
	static RepeaterConfigSnapshot buildRepeaterSnapshotForAppliedCache(
		RepeaterConfigSnapshot currentSnapshot,
		LinkNodeType cacheType,
		List<Long> nextOrderedSerials
	) {
		RepeaterConfigSnapshot normalizedSnapshot = currentSnapshot == null ? RepeaterConfigSnapshot.empty() : currentSnapshot;
		String nextExpression = buildFilterSerialExpression(nextOrderedSerials);
		if (cacheType == LinkNodeType.TRIGGER_SOURCE) {
			return new RepeaterConfigSnapshot(
				nextExpression,
				normalizedSnapshot.outputSerialExpression(),
				normalizedSnapshot.delay()
			);
		}
		return new RepeaterConfigSnapshot(
			normalizedSnapshot.inputSerialExpression(),
			nextExpression,
			normalizedSnapshot.delay()
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
	 * 解析频道模式过滤器应用成功反馈的翻译键。
	 */
	static String filterChannelApplySuccessMessageKey(LinkFilterKind filterKind) {
		return filterKind == LinkFilterKind.RECEIVE
			? "message.redstonelink.quick_link.apply.done.receive_filter_channel"
			: "message.redstonelink.quick_link.apply.done.send_filter_channel";
	}

	/**
	 * 解析转发器 quick-link 应用成功反馈的翻译键。
	 */
	static String repeaterApplySuccessMessageKey(LinkNodeType cacheType) {
		return cacheType == LinkNodeType.TRIGGER_SOURCE
			? "message.redstonelink.quick_link.apply.done.repeater_input"
			: "message.redstonelink.quick_link.apply.done.repeater_output";
	}

	/**
	 * 将写控判定映射为统一前端提示。
	 */
	static QuickLinkOperationFeedback failureFromWriteDecision(LinkWriteControlService.WriteDecision writeDecision) {
		return QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient");
	}

	/**
	 * 将命令层结构化反馈转成 quick-link 反馈。
	 */
	static QuickLinkOperationFeedback toQuickLinkFeedback(LinkSetExecutionService.OperationFeedback feedback) {
		if (feedback == null) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient");
		}
		return feedback.success()
			? QuickLinkOperationFeedback.success(feedback.messageKey(), feedback.messageArgs().toArray(String[]::new))
			: QuickLinkOperationFeedback.failure(feedback.messageKey(), feedback.messageArgs().toArray(String[]::new));
	}

	/**
	 * 构造来源视角 quick-link 应用后的目标集合。
	 */
	static Set<Long> buildNextSourceTargets(
		Set<Long> currentTargets,
		List<Long> cachedTargets,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		Set<Long> normalizedCurrentTargets = normalizePositiveSerialSet(currentTargets);
		Set<Long> normalizedCachedTargets = normalizePositiveSerialSet(cachedTargets);
		QuickLinkToolData.ApplyEditMode resolvedApplyEditMode = normalizeApplyEditMode(applyEditMode);
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.REPLACE) {
			return normalizedCachedTargets;
		}

		Set<Long> nextTargets = new HashSet<>(normalizedCurrentTargets);
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.APPEND) {
			nextTargets.addAll(normalizedCachedTargets);
		} else {
			nextTargets.removeAll(normalizedCachedTargets);
		}
		return nextTargets.isEmpty() ? Set.of() : Set.copyOf(nextTargets);
	}

	/**
	 * 构造 `core` 视角 quick-link 应用后的来源集合。
	 */
	static List<Long> buildDesiredTriggerSourcesForCoreApply(
		Set<Long> currentTriggerSources,
		List<Long> cachedTriggerSources,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		LinkedHashSet<Long> normalizedCachedTriggerSources = normalizePositiveOrderedSerials(cachedTriggerSources);
		QuickLinkToolData.ApplyEditMode resolvedApplyEditMode = normalizeApplyEditMode(applyEditMode);
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.REPLACE) {
			return List.copyOf(normalizedCachedTriggerSources);
		}

		LinkedHashSet<Long> nextTriggerSources = new LinkedHashSet<>(sortedPositiveSerials(currentTriggerSources));
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.APPEND) {
			nextTriggerSources.addAll(normalizedCachedTriggerSources);
		} else {
			nextTriggerSources.removeAll(normalizedCachedTriggerSources);
		}
		return List.copyOf(nextTriggerSources);
	}

	/**
	 * 构造过滤器 quick-link 应用后的有序序号集合。
	 */
	static List<Long> buildNextFilterOrderedSerials(
		List<Long> currentOrderedSerials,
		List<Long> cachedOrderedSerials,
		QuickLinkToolData.ApplyEditMode applyEditMode
	) {
		LinkedHashSet<Long> normalizedCachedOrderedSerials = normalizePositiveOrderedSerials(cachedOrderedSerials);
		QuickLinkToolData.ApplyEditMode resolvedApplyEditMode = normalizeApplyEditMode(applyEditMode);
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.REPLACE) {
			return List.copyOf(normalizedCachedOrderedSerials);
		}

		LinkedHashSet<Long> nextOrderedSerials = normalizePositiveOrderedSerials(currentOrderedSerials);
		if (resolvedApplyEditMode == QuickLinkToolData.ApplyEditMode.APPEND) {
			nextOrderedSerials.addAll(normalizedCachedOrderedSerials);
		} else {
			nextOrderedSerials.removeAll(normalizedCachedOrderedSerials);
		}
		return List.copyOf(nextOrderedSerials);
	}

	/**
	 * 解析过滤器当前表达式中的有序序号集合。
	 */
	static List<Long> parseFilterOrderedSerials(String serialExpression) {
		return List.copyOf(SerialParseUtil.parseTargetsOrdered(serialExpression, 0).orderedTargets());
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
	 * 校验区块激活器应用后是否会超过 resident 总上限。
	 */
	private static QuickLinkOperationFeedback validateChunkActivatorResidentCapacity(
		ServerLevel level,
		LinkChunkActivatorBlockEntity chunkActivatorBlockEntity,
		ChunkActivatorConfigStateSnapshot nextSnapshot
	) {
		if (level == null || chunkActivatorBlockEntity == null || nextSnapshot == null) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		int effectiveResidents = CrossChunkEffectiveWhitelistService.countDistinctResidentsAfterActivatorChange(
			level,
			level.dimension(),
			chunkActivatorBlockEntity.getBlockPos(),
			nextSnapshot,
			chunkActivatorBlockEntity.active()
		);
		int residentLimit = RedstoneLinkConfig.crossChunk().residentMaxEntries();
		if (effectiveResidents <= residentLimit) {
			return null;
		}
		return QuickLinkOperationFeedback.failure(
			"message.redstonelink.chunk_activator.resident.limit_exceeded",
			Integer.toString(effectiveResidents),
			Integer.toString(residentLimit)
		);
	}

	/**
	 * 归一化应用编辑模式，空值回退为 `replace`。
	 */
	private static QuickLinkToolData.ApplyEditMode normalizeApplyEditMode(QuickLinkToolData.ApplyEditMode applyEditMode) {
		return applyEditMode == null ? QuickLinkToolData.ApplyEditMode.REPLACE : applyEditMode;
	}

	/**
	 * `replace` 允许空缓存执行“覆盖为空”，其余模式仍要求缓存非空。
	 */
	static boolean allowsEmptySerialCacheApply(QuickLinkToolData.ApplyEditMode applyEditMode) {
		return normalizeApplyEditMode(applyEditMode) == QuickLinkToolData.ApplyEditMode.REPLACE;
	}

	/**
	 * 归一化正整数集合。
	 */
	private static Set<Long> normalizePositiveSerialSet(Iterable<Long> serials) {
		if (serials == null) {
			return Set.of();
		}
		Set<Long> normalized = new HashSet<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
	}

	/**
	 * 归一化有序正整数集合，并保留首次出现顺序。
	 */
	private static LinkedHashSet<Long> normalizePositiveOrderedSerials(Iterable<Long> serials) {
		LinkedHashSet<Long> normalized = new LinkedHashSet<>();
		if (serials == null) {
			return normalized;
		}
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		return normalized;
	}

	/**
	 * 将序号集合归一化为升序列表，供 `core` 视角保持稳定输出顺序。
	 */
	private static List<Long> sortedPositiveSerials(Iterable<Long> serials) {
		List<Long> normalized = new java.util.ArrayList<>(normalizePositiveOrderedSerials(serials));
		normalized.sort(Long::compareTo);
		return normalized;
	}

	/**
	 * @return 当前命令源是否具备 limited 模式越权权限
	 */
	private static boolean hasLimitedBypassPermission(CommandSourceStack commandSource) {
		return commandSource != null && commandSource.hasPermission(RedstoneLinkConfig.writeControl().limitedPermissionLevel());
	}

	/**
	 * @return 当前命令源是否具备 protected 模式越权权限
	 */
	private static boolean hasProtectedBypassPermission(CommandSourceStack commandSource) {
		return commandSource != null && commandSource.hasPermission(RedstoneLinkConfig.writeControl().protectedPermissionLevel());
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
