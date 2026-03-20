package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.block.entity.LinkSyncLeverBlockEntity;
import com.makomi.util.SignalStrengths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 内部同步派发 delta 事件总线。
 * <p>
 * 该总线仅服务于模组内部“信道变更 -> 目标投影”链路：
 * 1. 采用同步发布，保证同 tick 内处理顺序确定；
 * 2. 不依赖 API 层事件，避免业务实现耦合到 `com.makomi.api.v1.*`；
 * 3. 作为后续 P6-3/P7/P8 的统一生产端骨架。
 * </p>
 */
public final class InternalDispatchDeltaEvents {
	private static final int RECENT_EVENT_CACHE_LIMIT = 8_192;
	private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
	private static final LinkedHashSet<DispatchDedupKey> RECENT_EVENT_KEYS = new LinkedHashSet<>();

	private InternalDispatchDeltaEvents() {
	}

	/**
	 * 事件监听器。
	 */
	public interface Listener {
		/**
		 * 处理一条内部派发 delta 事件。
		 *
		 * @param event 事件快照
		 */
		void onDispatchDelta(DispatchDeltaEvent event);
	}

	/**
	 * 注册内部监听器。
	 *
	 * @param listener 监听器
	 */
	public static void register(Listener listener) {
		if (listener == null || LISTENERS.contains(listener)) {
			return;
		}
		LISTENERS.add(listener);
	}

	/**
	 * 注销内部监听器。
	 *
	 * @param listener 监听器
	 */
	public static void unregister(Listener listener) {
		if (listener == null) {
			return;
		}
		LISTENERS.remove(listener);
	}

	/**
	 * 同步发布事件。
	 *
	 * @param event 事件快照
	 */
	public static void publish(DispatchDeltaEvent event) {
		if (event == null) {
			return;
		}
		if (!markPublished(DispatchDedupKey.from(event))) {
			return;
		}
		for (Listener listener : LISTENERS) {
			listener.onDispatchDelta(event);
		}
	}

	/**
	 * 发布“链路解绑”对应的来源失效事件。
	 * <p>
	 * 说明：
	 * 1. 输入允许使用命令视角 `triggerSource/core` 作为来源类型；
	 * 2. 方法内部会统一映射到实际方向 `triggerSource -> core`；
	 * 3. 每条解绑关系仅发布一条“来源全量失效”remove 事件。
	 * </p>
	 *
	 * @param sourceLevel 事件所在服务端维度
	 * @param linkViewSourceType 当前链路写操作视角下的“来源类型”
	 * @param linkViewSourceSerial 当前链路写操作视角下的“来源序号”
	 * @param detachedSerials 被解绑的另一端序号集合
	 * @param eventMeta 事件时间元数据
	 */
	public static void publishLinkDetached(
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
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			detachedSerials,
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
	 * 发布“链路解绑”对应的来源失效事件（单目标入口）。
	 */
	public static void publishLinkDetached(
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
	 * 发布“链路建立/恢复”对应的来源增量事件（UPSERT）。
	 * <p>
	 * 该入口用于 P6-3 统一生产端：将 `link add/set 新增`、来源/目标重建等场景统一翻译为可投影 delta。
	 * 当前仅对可解析的同步来源状态生成 `SYNC_SIGNAL + UPSERT`。
	 * </p>
	 *
	 * @param sourceLevel 事件所在服务端维度
	 * @param linkViewSourceType 当前链路写操作视角下的“来源类型”
	 * @param linkViewSourceSerial 当前链路写操作视角下的“来源序号”
	 * @param attachedSerials 被新增绑定的另一端序号集合
	 * @param eventMeta 事件时间元数据
	 */
	public static void publishLinkAttached(
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
		// 同一批 attach 内，同一来源仅解析一次回放强度，避免重复节点查找与方块实体读取。
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
	 * 发布“链路建立/恢复”对应的来源增量事件（单目标入口）。
	 */
	public static void publishLinkAttached(
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
	 * 发布单条“来源失效”事件（一次剔除该来源在目标上的全部贡献）。
	 *
	 * @param sourceLevel 来源所在维度上下文
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标类型
	 * @param targetSerial 目标序号
	 * @param eventMeta 事件时间元数据
	 */
	public static void publishSourceInvalidation(
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

		publish(
			new DispatchDeltaEvent(
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
	 * 发布“来源恢复”对应的增量事件（当前只覆盖 sync 强度态）。
	 */
	private static void publishSourceRebuildUpsert(
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
	 * 按已解析强度发布来源恢复 UPSERT（避免重复查询来源状态）。
	 */
	private static void publishSourceRebuildUpsertResolved(
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
		publish(
			new DispatchDeltaEvent(
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
	 * 尝试解析来源的“可恢复同步强度”。
	 * <p>
	 * 返回值语义：
	 * 1. `>=0`：可恢复，值即当前同步强度；
	 * 2. `<0`：当前来源不具备可恢复同步态（例如 toggle/pulse 源）。
	 * </p>
	 */
	private static int resolveReplaySyncStrength(ServerLevel contextLevel, LinkNodeType sourceType, long sourceSerial) {
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
	static Set<SourceTargetPair> normalizeLinkPairsForTesting(
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		Set<Long> peerSerials
	) {
		Set<SourceTargetPair> pairs = new LinkedHashSet<>();
		forEachNormalizedPair(
			linkViewSourceType,
			linkViewSourceSerial,
			peerSerials,
			(sourceSerial, targetSerial) -> pairs.add(new SourceTargetPair(sourceSerial, targetSerial))
		);
		return Set.copyOf(pairs);
	}

	/**
	 * 归一化后的 `triggerSource -> core` 序号对。
	 */
	record SourceTargetPair(long sourceSerial, long targetSerial) {}

	/**
	 * 记录事件发布去重键；用于同 tick 防重与防环。
	 */
	private record DispatchDedupKey(
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		ActivatableTargetBlockEntity.DeltaKind deltaKind,
		ActivatableTargetBlockEntity.DeltaAction deltaAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		long tick,
		int slot,
		long seq
	) {
		private static DispatchDedupKey from(DispatchDeltaEvent event) {
			if (event == null) {
				return null;
			}
			long tick = event.eventMeta() == null ? 0L : event.eventMeta().timeKey().tick();
			int slot = event.eventMeta() == null ? 0 : event.eventMeta().timeKey().slot();
			long seq = event.eventMeta() == null ? 0L : event.eventMeta().seq();
			return new DispatchDedupKey(
				event.sourceType(),
				event.sourceSerial(),
				event.targetType(),
				event.targetSerial(),
				event.deltaKind(),
				event.deltaAction(),
				event.activationMode(),
				event.syncSignalStrength(),
				tick,
				slot,
				seq
			);
		}
	}

	/**
	 * 将事件标记为“本窗口首发”；重复事件返回 false。
	 */
	private static boolean markPublished(DispatchDedupKey key) {
		if (key == null) {
			return false;
		}
		synchronized (RECENT_EVENT_KEYS) {
			if (RECENT_EVENT_KEYS.contains(key)) {
				return false;
			}
			RECENT_EVENT_KEYS.add(key);
			while (RECENT_EVENT_KEYS.size() > RECENT_EVENT_CACHE_LIMIT) {
				Iterator<DispatchDedupKey> iterator = RECENT_EVENT_KEYS.iterator();
				if (!iterator.hasNext()) {
					break;
				}
				iterator.next();
				iterator.remove();
			}
			return true;
		}
	}

	/**
	 * 测试专用：重置监听器与防重缓存。
	 */
	static void resetForTesting() {
		LISTENERS.clear();
		synchronized (RECENT_EVENT_KEYS) {
			RECENT_EVENT_KEYS.clear();
		}
	}

	/**
	 * 内部派发 delta 事件快照。
	 *
	 * @param sourceLevel 事件上下文维度（用于定位目标在线状态和跨区块入队）
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标类型
	 * @param targetSerial 目标序号
	 * @param deltaKind 语义类型（activation/sync/source_invalidation）
	 * @param deltaAction 动作（upsert/remove）
	 * @param activationMode 激活模式（activation 生效；sync 固定 toggle）
	 * @param syncSignalStrength 同步强度（sync 生效）
	 * @param eventMeta 事件时间元数据
	 */
	public record DispatchDeltaEvent(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		ActivatableTargetBlockEntity.DeltaKind deltaKind,
		ActivatableTargetBlockEntity.DeltaAction deltaAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		EventMeta eventMeta
	) {
		public DispatchDeltaEvent {
			activationMode = activationMode == null ? ActivationMode.TOGGLE : activationMode;
			syncSignalStrength = SignalStrengths.clamp(syncSignalStrength);
			eventMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;
		}
	}
}
