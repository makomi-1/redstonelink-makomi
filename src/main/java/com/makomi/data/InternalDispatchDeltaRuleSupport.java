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
				eventMeta
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
		if (
			sourceLevel == null
				|| linkViewSourceType == null
				|| linkViewSourceSerial <= 0L
				|| attachedSerials == null
				|| attachedSerials.isEmpty()
		) {
			return;
		}
		Map<Long, Integer> replayStrengthBySourceSerial = new HashMap<>();
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
				publishSourceRebuildUpsertResolved(
					sourceLevel,
					LinkNodeType.TRIGGER_SOURCE,
					sourceSerial,
					LinkNodeType.CORE,
					targetSerial,
					normalizedMeta,
					replayStrength
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
				publishResolvedTargetChunkLoadSyncReplay(sourceLevel, sourceSerial, targetSerial, replaySnapshot);
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
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerial,
			(sourceSerial, targetSerial) -> publishSourceRebuildUpsert(
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
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return;
		}
		if (!RedstoneLinkConfig.crossChunk().triggerSourceChunkUnloadInvalidationEnabled()) {
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
				normalizedMeta
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
		if (!RedstoneLinkConfig.crossChunk().triggerSourceInvalidationEnabled()) {
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
				normalizedMeta
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
			replaySnapshot.signalStrength()
		);
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
		LevelChunk sourceChunk = chunkSource.getChunkNow(sourceNode.pos().getX() >> 4, sourceNode.pos().getZ() >> 4);
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
		LevelChunk sourceChunk = chunkSource.getChunkNow(sourceNode.pos().getX() >> 4, sourceNode.pos().getZ() >> 4);
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
