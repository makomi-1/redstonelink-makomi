package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.block.entity.LinkSyncLeverBlockEntity;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SignalStrengths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * InternalDispatchDeltaEvents 规则 helper。
 * <p>
 * 负责链路视角归一化、delta 聚合规则、来源回放解析与 replay 构造。
 * </p>
 */
final class InternalDispatchDeltaRuleSupport {
	private InternalDispatchDeltaRuleSupport() {
	}

	/**
	 * 发布“链路解绑”对应的来源失效事件。
	 */
	static void publishLinkDetached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> detachedSerials,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| detachedSerials == null
				|| detachedSerials.isEmpty()
		) {
			return;
		}
		publishTriggerSourceInvalidation(sourceLevel, linkViewSourceType, linkViewSourceSerial, detachedSerials, eventMeta);
	}

	/**
	 * 发布“triggerSource 区块卸载失效”事件（集合入口）。
	 */
	static void publishLinkChunkUnloaded(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		publishLinkChunkUnloaded(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			eventMeta,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 发布“triggerSource 区块卸载失效”事件（集合入口），并指定交付模式。
	 */
	static void publishLinkChunkUnloaded(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> affectedSerials,
		EventMeta eventMeta,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| affectedSerials == null
				|| affectedSerials.isEmpty()
		) {
			return;
		}
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			(sourceSerial, targetSerial) -> publishTriggerSourceChunkUnloadInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				sourceSerial,
				LinkNodeType.CORE,
				targetSerial,
				eventMeta,
				deliveryMode
			)
		);
	}

	/**
	 * 发布“链路解绑”对应的 triggerSource 其它失效事件（单目标入口）。
	 */
	static void publishLinkDetached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		long detachedSerial,
		EventMeta eventMeta
	) {
		if (detachedSerial <= 0L) {
			return;
		}
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			detachedSerial,
			(sourceSerial, targetSerial) -> publishTriggerSourceInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				sourceSerial,
				LinkNodeType.CORE,
				targetSerial,
				eventMeta
			)
		);
	}

	/**
	 * 发布“链路建立/恢复”对应的来源增量事件（UPSERT）。
	 */
	static void publishLinkAttached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> attachedSerials,
		EventMeta eventMeta
	) {
		publishLinkAttached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			eventMeta,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 发布“链路建立/恢复”对应的来源增量事件（UPSERT），并指定交付模式。
	 */
	static void publishLinkAttached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> attachedSerials,
		EventMeta eventMeta,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| attachedSerials == null
				|| attachedSerials.isEmpty()
		) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		Map<Long, Integer> replayStrengthBySourceSerial = new HashMap<>();
		Map<Long, LinkSavedData.LinkNode> triggerSourceNodeBySerial = new HashMap<>();
		Map<Long, LinkSavedData.LinkNode> coreNodeBySerial = new HashMap<>();
		EventMeta normalizedMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			(sourceSerial, targetSerial) -> {
				int replayStrength = replayStrengthBySourceSerial.computeIfAbsent(
					sourceSerial,
					serial -> resolveReplaySyncStrength(sourceLevel, LinkNodeType.TRIGGER_SOURCE, serial)
				);
				// 新增边 attach replay 必须与其它 replay 路径保持同一过滤口径；
				// 若持久化 send/receive 过滤器拦截，则不再补发本次恢复事件。
				if (
					!allowsLinkAttachedReplayByPersistedFilters(
						sourceLevel,
						savedData,
						sourceSerial,
						targetSerial,
						replayStrength,
						triggerSourceNodeBySerial,
						coreNodeBySerial
					)
				) {
					return;
				}
				publishSourceRebuildUpsertResolved(
					sourceLevel,
					LinkNodeType.TRIGGER_SOURCE,
					sourceSerial,
					LinkNodeType.CORE,
					targetSerial,
					normalizedMeta,
					replayStrength,
					deliveryMode
				);
			}
		);
	}

	/**
	 * 发布“目标区块加载”场景下的 sync 恢复事件。
	 */
	static void publishLinkAttachedFromTargetChunkLoad(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> attachedSerials
	) {
		publishLinkAttachedFromTargetChunkLoad(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 发布“目标区块加载”场景下的 sync 恢复事件，并指定交付模式。
	 */
	static void publishLinkAttachedFromTargetChunkLoad(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> attachedSerials,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| attachedSerials == null
				|| attachedSerials.isEmpty()
		) {
			return;
		}
		Map<Long, SyncReplaySourceBlockEntity.ReplaySyncSnapshot> replaySnapshotBySourceSerial = new HashMap<>();
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			(sourceSerial, targetSerial) -> {
				SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot = replaySnapshotBySourceSerial.computeIfAbsent(
					sourceSerial,
					serial -> resolveReplaySyncSnapshot(sourceLevel, LinkNodeType.TRIGGER_SOURCE, serial)
				);
				publishResolvedTargetChunkLoadSyncReplay(sourceLevel, sourceSerial, targetSerial, replaySnapshot, deliveryMode);
			}
		);
	}

	/**
	 * 发布“链路建立/恢复”对应的来源增量事件（单目标入口）。
	 */
	static void publishLinkAttached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		long attachedSerial,
		EventMeta eventMeta
	) {
		if (attachedSerial <= 0L) {
			return;
		}
		publishLinkAttached(sourceLevel, linkViewSourceType, linkViewSourceSerial, Set.of(attachedSerial), eventMeta);
	}

	/**
	 * 判断新增边 attach replay 是否允许通过持久化 send/receive 过滤器。
	 * <p>
	 * 若当前上下文无法解析服务端或节点快照，则回退到旧行为放行，避免测试夹具或异常态误伤；
	 * 正常运行时会在已解析出的 `triggerSource -> core` 配对上严格按双侧过滤真值补判。
	 * </p>
	 */
	static boolean allowsLinkAttachedReplayByPersistedFilters(
		ServerLevel sourceLevel,
		long triggerSourceSerial,
		long coreSerial,
		int replayStrength
	) {
		if (sourceLevel == null) {
			return true;
		}
		return allowsLinkAttachedReplayByPersistedFilters(
			sourceLevel,
			LinkSavedData.get(sourceLevel),
			triggerSourceSerial,
			coreSerial,
			replayStrength,
			new HashMap<>(),
			new HashMap<>()
		);
	}

	/**
	 * attach replay 过滤补判的共享实现。
	 */
	private static boolean allowsLinkAttachedReplayByPersistedFilters(
		ServerLevel sourceLevel,
		LinkSavedData savedData,
		long triggerSourceSerial,
		long coreSerial,
		int replayStrength,
		Map<Long, LinkSavedData.LinkNode> triggerSourceNodeBySerial,
		Map<Long, LinkSavedData.LinkNode> coreNodeBySerial
	) {
		if (
			sourceLevel == null
				|| savedData == null
				|| triggerSourceSerial <= 0L
				|| coreSerial <= 0L
				|| replayStrength < 0
				|| sourceLevel.getServer() == null
		) {
			return replayStrength >= 0;
		}
		LinkSavedData.LinkNode triggerSourceNode = resolveCachedNode(
			triggerSourceNodeBySerial,
			savedData,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial
		);
		LinkSavedData.LinkNode coreNode = resolveCachedNode(coreNodeBySerial, savedData, LinkNodeType.CORE, coreSerial);
		if (triggerSourceNode == null || coreNode == null) {
			return true;
		}
		return LinkDispatchFilterService.allowsReplayByPersistedFilters(
			sourceLevel.getServer(),
			triggerSourceNode.dimension(),
			triggerSourceNode.pos(),
			triggerSourceSerial,
			coreNode.dimension(),
			coreNode.pos(),
			coreSerial,
			replayStrength
		);
	}

	/**
	 * 以允许缓存 null 值的方式复用节点快照查找，避免同一批新增边重复命中 SavedData。
	 */
	private static LinkSavedData.LinkNode resolveCachedNode(
		Map<Long, LinkSavedData.LinkNode> cache,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		if (cache.containsKey(serial)) {
			return cache.get(serial);
		}
		LinkSavedData.LinkNode node = savedData.findNode(nodeType, serial).orElse(null);
		cache.put(serial, node);
		return node;
	}

	/**
	 * 发布“triggerSource 其它失效”事件（集合入口）。
	 */
	static void publishTriggerSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| affectedSerials == null
				|| affectedSerials.isEmpty()
		) {
			return;
		}
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			(sourceSerial, targetSerial) -> publishTriggerSourceInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				sourceSerial,
				LinkNodeType.CORE,
				targetSerial,
				eventMeta
			)
		);
	}

	/**
	 * 发布“sync 来源失效”事件（集合入口）。
	 */
	static void publishSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| affectedSerials == null
				|| affectedSerials.isEmpty()
		) {
			return;
		}
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			(sourceSerial, targetSerial) -> publishSourceInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				sourceSerial,
				LinkNodeType.CORE,
				targetSerial,
				eventMeta
			)
		);
	}

	/**
	 * 发布单条“triggerSource 区块卸载失效”事件。
	 */
	static void publishTriggerSourceChunkUnloadInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		publishTriggerSourceChunkUnloadInvalidation(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 发布单条“triggerSource 区块卸载失效”事件，并指定交付模式。
	 */
	static void publishTriggerSourceChunkUnloadInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return;
		}
		if (!RedstoneLinkConfig.crossChunk().triggerSourceContextDetachInvalidationEnabled()) {
			return;
		}
		EventMeta normalizedMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;

		InternalDispatchDeltaEvents.publish(
			new InternalDispatchDeltaEvents.DispatchDeltaEvent(
				sourceLevel,
				sourceType,
				sourceSerial,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
				ActivatableTargetBlockEntity.DeltaAction.REMOVE,
				ActivationMode.TOGGLE,
				0,
				normalizedMeta,
				deliveryMode
			)
		);
	}

	/**
	 * 发布单条“triggerSource 其它失效”事件。
	 */
	static void publishTriggerSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return;
		}
		if (!TriggerSourceEffectiveActivationPolicy.hardDownFilteringEnabled()) {
			return;
		}
		EventMeta normalizedMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;

		InternalDispatchDeltaEvents.publish(
			new InternalDispatchDeltaEvents.DispatchDeltaEvent(
				sourceLevel,
				sourceType,
				sourceSerial,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
				ActivatableTargetBlockEntity.DeltaAction.REMOVE,
				ActivationMode.TOGGLE,
				0,
				normalizedMeta
			)
		);
	}

	/**
	 * 发布单条“sync 来源失效”事件。
	 */
	static void publishSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return;
		}
		EventMeta normalizedMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;

		InternalDispatchDeltaEvents.publish(
			new InternalDispatchDeltaEvents.DispatchDeltaEvent(
				sourceLevel,
				sourceType,
				sourceSerial,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION,
				ActivatableTargetBlockEntity.DeltaAction.REMOVE,
				ActivationMode.TOGGLE,
				0,
				normalizedMeta
			)
		);
	}

	/**
	 * 发布“来源恢复”对应的增量事件。
	 */
	static void publishSourceRebuildUpsert(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return;
		}

		int replayStrength = resolveReplaySyncStrength(sourceLevel, sourceType, sourceSerial);
		publishSourceRebuildUpsertResolved(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta,
			replayStrength
		);
	}

	/**
	 * 按已解析强度发布来源恢复 UPSERT。
	 */
	static void publishSourceRebuildUpsertResolved(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta,
		int replayStrength
	) {
		publishSourceRebuildUpsertResolved(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta,
			replayStrength,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 按已解析强度发布来源恢复 UPSERT，并指定交付模式。
	 */
	static void publishSourceRebuildUpsertResolved(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta,
		int replayStrength,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (replayStrength < 0) {
			return;
		}
		EventMeta normalizedMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;
		InternalDispatchDeltaEvents.publish(
			new InternalDispatchDeltaEvents.DispatchDeltaEvent(
				sourceLevel,
				sourceType,
				sourceSerial,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				ActivatableTargetBlockEntity.DeltaAction.UPSERT,
				ActivationMode.TOGGLE,
				replayStrength,
				normalizedMeta,
				deliveryMode
			)
		);
	}

	/**
	 * 按已解析快照发布 `CHUNK_LOAD` 专用 sync replay。
	 */
	static void publishResolvedTargetChunkLoadSyncReplay(
		ServerLevel sourceLevel,
		long sourceSerial,
		long targetSerial,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot
	) {
		publishResolvedTargetChunkLoadSyncReplay(
			sourceLevel,
			sourceSerial,
			targetSerial,
			replaySnapshot,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);
	}

	/**
	 * 按已解析快照发布 `CHUNK_LOAD` 专用 sync replay，并指定交付模式。
	 */
	static void publishResolvedTargetChunkLoadSyncReplay(
		ServerLevel sourceLevel,
		long sourceSerial,
		long targetSerial,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (sourceLevel == null || sourceSerial <= 0L || targetSerial <= 0L || replaySnapshot == null) {
			return;
		}
		publishSourceRebuildUpsertResolved(
			sourceLevel,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			LinkNodeType.CORE,
			targetSerial,
			replaySnapshot.eventMeta(),
			replaySnapshot.signalStrength(),
			deliveryMode
		);
	}

	/**
	 * 按“当前真值优先、持久化 replay 快照兜底”补发一次来源 sync。
	 * <p>
	 * 用于区块激活器节点集新增场景：若来源区块当前已加载，则优先按 live truth 重放；
	 * 若来源区块未加载，则回退到最近一次已持久化的 replay 快照。
	 * </p>
	 */
	static void publishTriggerSourceCurrentOrPersistedSyncReplay(
		ServerLevel sourceLevel,
		long sourceSerial,
		Set<Long> targetSerials,
		InternalDispatchDeltaEvents.DeliveryMode deliveryMode
	) {
		if (sourceLevel == null || sourceSerial <= 0L || targetSerials == null || targetSerials.isEmpty()) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		LinkSavedData.LinkNode triggerSourceNode = savedData.findNode(LinkNodeType.TRIGGER_SOURCE, sourceSerial).orElse(null);
		if (triggerSourceNode == null) {
			return;
		}

		int liveReplayStrength = resolveReplaySyncStrength(sourceLevel, LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		EventMeta liveReplayMeta = liveReplayStrength >= 0 ? EventMeta.now(sourceLevel) : null;
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot fallbackReplaySnapshot = liveReplayStrength >= 0
			? null
			: resolveReplaySyncSnapshot(sourceLevel, LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		if (liveReplayStrength < 0 && fallbackReplaySnapshot == null) {
			return;
		}

		Map<Long, LinkSavedData.LinkNode> triggerSourceNodeBySerial = new HashMap<>();
		Map<Long, LinkSavedData.LinkNode> coreNodeBySerial = new HashMap<>();
		triggerSourceNodeBySerial.put(sourceSerial, triggerSourceNode);
		int filterStrength = liveReplayStrength >= 0 ? liveReplayStrength : fallbackReplaySnapshot.signalStrength();
		for (Long targetSerial : targetSerials) {
			if (targetSerial == null || targetSerial <= 0L) {
				continue;
			}
			if (
				!allowsLinkAttachedReplayByPersistedFilters(
					sourceLevel,
					savedData,
					sourceSerial,
					targetSerial,
					filterStrength,
					triggerSourceNodeBySerial,
					coreNodeBySerial
				)
			) {
				continue;
			}
			if (liveReplayStrength >= 0) {
				publishSourceRebuildUpsertResolved(
					sourceLevel,
					LinkNodeType.TRIGGER_SOURCE,
					sourceSerial,
					LinkNodeType.CORE,
					targetSerial,
					liveReplayMeta,
					liveReplayStrength,
					deliveryMode
				);
				continue;
			}
			publishResolvedTargetChunkLoadSyncReplay(sourceLevel, sourceSerial, targetSerial, fallbackReplaySnapshot, deliveryMode);
		}
	}

	/**
	 * 尝试解析来源的“可恢复同步强度”。
	 */
	static int resolveReplaySyncStrength(ServerLevel contextLevel, LinkNodeType sourceType, long sourceSerial) {
		if (contextLevel == null || sourceType == null || sourceSerial <= 0L) {
			return -1;
		}
		LinkSavedData savedData = LinkSavedData.get(contextLevel);
		LinkSavedData.LinkNode sourceNode = savedData.findNode(sourceType, sourceSerial).orElse(null);
		if (sourceNode == null) {
			return -1;
		}
		ServerLevel sourceNodeLevel = contextLevel.getServer().getLevel(sourceNode.dimension());
		if (sourceNodeLevel == null) {
			return -1;
		}
		ServerChunkCache chunkSource = sourceNodeLevel.getChunkSource();
		if (chunkSource == null) {
			return -1;
		}
		LevelChunk sourceChunk;
		try {
			sourceChunk = chunkSource.getChunkNow(sourceNode.pos().getX() >> 4, sourceNode.pos().getZ() >> 4);
		} catch (RuntimeException ex) {
			return -1;
		}
		if (sourceChunk == null) {
			return -1;
		}
		BlockEntity sourceBlockEntity = sourceChunk.getBlockEntity(sourceNode.pos(), LevelChunk.EntityCreationType.CHECK);
		if (sourceBlockEntity instanceof LinkSyncEmitterBlockEntity syncEmitterBlockEntity) {
			return SignalStrengths.clamp(syncEmitterBlockEntity.getLastObservedSignalStrength());
		}
		if (sourceBlockEntity instanceof LinkSyncLeverBlockEntity) {
			BlockState sourceState = sourceChunk.getBlockState(sourceNode.pos());
			if (sourceState.hasProperty(BlockStateProperties.POWERED)) {
				return sourceState.getValue(BlockStateProperties.POWERED) ? 15 : 0;
			}
			return 0;
		}
		return -1;
	}

	/**
	 * 尝试解析来源端最近一次真实 sync 派发快照。
	 */
	static SyncReplaySourceBlockEntity.ReplaySyncSnapshot resolveReplaySyncSnapshot(
		ServerLevel contextLevel,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		if (contextLevel == null || sourceType == null || sourceSerial <= 0L) {
			return null;
		}
		LinkSavedData savedData = LinkSavedData.get(contextLevel);
		LinkSavedData.LinkNode sourceNode = savedData.findNode(sourceType, sourceSerial).orElse(null);
		if (sourceNode == null) {
			return null;
		}
		ServerLevel sourceNodeLevel = contextLevel.getServer().getLevel(sourceNode.dimension());
		if (sourceNodeLevel == null) {
			return null;
		}
		ServerChunkCache chunkSource = sourceNodeLevel.getChunkSource();
		LevelChunk sourceChunk = null;
		if (chunkSource != null) {
			try {
				sourceChunk = chunkSource.getChunkNow(sourceNode.pos().getX() >> 4, sourceNode.pos().getZ() >> 4);
			} catch (RuntimeException ex) {
				sourceChunk = null;
			}
		}
		if (sourceChunk != null) {
			BlockEntity sourceBlockEntity = sourceChunk.getBlockEntity(sourceNode.pos(), LevelChunk.EntityCreationType.CHECK);
			if (sourceBlockEntity instanceof SyncReplaySourceBlockEntity syncReplaySourceBlockEntity) {
				SyncReplaySourceBlockEntity.ReplaySyncSnapshot liveSnapshot = syncReplaySourceBlockEntity.replaySyncSnapshot()
					.orElse(null);
				if (liveSnapshot != null) {
					return liveSnapshot;
				}
			}
		}
		return savedData
			.getTriggerSourceReplaySyncSnapshot(sourceSerial)
			.map(snapshot -> new SyncReplaySourceBlockEntity.ReplaySyncSnapshot(snapshot.signalStrength(), snapshot.eventMeta()))
			.orElse(null);
	}

	/**
	 * 将“命令视角来源+对端集合”统一映射成 `triggerSource -> core` 方向的序号对。
	 */
	private static void forEachNormalizedPair(
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> peerSerials,
		BiConsumer<Long, Long> consumer
	) {
		if (linkViewSourceType == null || linkViewSourceSerial <= 0L || peerSerials == null || peerSerials.isEmpty() || consumer == null) {
			return;
		}
		for (Long peerSerial : peerSerials) {
			if (peerSerial == null || peerSerial <= 0L) {
				continue;
			}
			forEachNormalizedPair(linkViewSourceType, linkViewSourceSerial, peerSerial, consumer);
		}
	}

	/**
	 * 将单目标链路视角归一化为 `triggerSource -> core` 序号对。
	 */
	private static void forEachNormalizedPair(
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		long peerSerial,
		BiConsumer<Long, Long> consumer
	) {
		if (
			linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| peerSerial <= 0L
				|| consumer == null
		) {
			return;
		}
		if (linkViewSourceType == LinkNodeType.TRIGGER_SOURCE) {
			consumer.accept(linkViewSourceSerial, peerSerial);
			return;
		}
		if (linkViewSourceType == LinkNodeType.CORE) {
			consumer.accept(peerSerial, linkViewSourceSerial);
		}
	}

	/**
	 * 测试专用：归一化链路视角到 `triggerSource -> core` 序号对。
	 */
	static Set<InternalDispatchDeltaEvents.SourceTargetPair> normalizeLinkPairsForTesting(
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> peerSerials
	) {
		Set<InternalDispatchDeltaEvents.SourceTargetPair> pairs = new LinkedHashSet<>();
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			peerSerials,
			(sourceSerial, targetSerial) -> pairs.add(new InternalDispatchDeltaEvents.SourceTargetPair(sourceSerial, targetSerial))
		);
		return Set.copyOf(pairs);
	}
}
