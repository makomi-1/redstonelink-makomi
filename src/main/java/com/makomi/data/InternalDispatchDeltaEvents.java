package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.util.SignalStrengths;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerLevel;

/**
 * 内部同步派发 delta 事件总线。
 * <p>
 * 主类保留静态 API、事件类型与去重键定义；
 * 具体职责拆分为：
 * 1. delta 聚合/归一化规则 helper；
 * 2. 监听器投递与同 tick 防重 helper。
 * </p>
 */
public final class InternalDispatchDeltaEvents {
	static final int RECENT_EVENT_CACHE_LIMIT = 8_192;
	static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
	static final LinkedHashSet<DispatchDedupKey> RECENT_EVENT_KEYS = new LinkedHashSet<>();

	private InternalDispatchDeltaEvents() {
	}

	/**
	 * 事件监听器。
	 */
	public interface Listener {
		/**
		 * 处理一条内部派发 delta 事件。
		 */
		void onDispatchDelta(DispatchDeltaEvent event);
	}

	/**
	 * 内部 delta 的目标端交付模式。
	 */
	public enum DeliveryMode {
		IMMEDIATE,
		ASYNC_BATCH
	}

	/**
	 * 注册内部监听器。
	 */
	public static void register(Listener listener) {
		InternalDispatchDeltaPublishSupport.register(listener);
	}

	/**
	 * 注销内部监听器。
	 */
	public static void unregister(Listener listener) {
		InternalDispatchDeltaPublishSupport.unregister(listener);
	}

	/**
	 * 同步发布事件。
	 */
	public static void publish(DispatchDeltaEvent event) {
		InternalDispatchDeltaPublishSupport.publish(event);
	}

	/**
	 * 发布“链路解绑”对应的来源失效事件。
	 */
	public static void publishLinkDetached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> detachedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkDetached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			detachedSerials,
			eventMeta
		);
	}

	/**
	 * 发布“triggerSource 区块卸载失效”事件（集合入口）。
	 */
	public static void publishLinkChunkUnloaded(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkChunkUnloaded(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			eventMeta
		);
	}

	/**
	 * 发布“triggerSource 区块卸载失效”事件，并优先走异步批提交。
	 */
	public static void publishLinkChunkUnloadedAsyncBatch(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkChunkUnloaded(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			eventMeta,
			DeliveryMode.ASYNC_BATCH
		);
	}

	/**
	 * 发布“链路解绑”对应的 triggerSource 其它失效事件（单目标入口）。
	 */
	public static void publishLinkDetached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		long detachedSerial,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkDetached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			detachedSerial,
			eventMeta
		);
	}

	/**
	 * 发布“链路建立/恢复”对应的来源增量事件（UPSERT）。
	 */
	public static void publishLinkAttached(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> attachedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkAttached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			eventMeta
		);
	}

	/**
	 * 发布“链路建立/恢复”对应的来源增量事件，并优先走异步批提交。
	 */
	public static void publishLinkAttachedAsyncBatch(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> attachedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishLinkAttached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			eventMeta,
			DeliveryMode.ASYNC_BATCH
		);
	}

	/**
	 * 发布“目标区块加载”场景下的 sync 恢复事件。
	 */
	public static void publishLinkAttachedFromTargetChunkLoad(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> attachedSerials
	) {
		InternalDispatchDeltaRuleSupport.publishLinkAttachedFromTargetChunkLoad(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials
		);
	}

	/**
	 * 发布“目标区块加载”场景下的 sync 恢复事件，并优先走异步批提交。
	 */
	public static void publishLinkAttachedFromTargetChunkLoadAsyncBatch(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> attachedSerials
	) {
		InternalDispatchDeltaRuleSupport.publishLinkAttachedFromTargetChunkLoad(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerials,
			DeliveryMode.ASYNC_BATCH
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
		InternalDispatchDeltaRuleSupport.publishLinkAttached(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			attachedSerial,
			eventMeta
		);
	}

	/**
	 * 发布“triggerSource 其它失效”事件（集合入口）。
	 */
	public static void publishTriggerSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishTriggerSourceInvalidation(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			eventMeta
		);
	}

	/**
	 * 发布“sync 来源失效”事件（集合入口）。
	 */
	public static void publishSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> affectedSerials,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishSourceInvalidation(
			sourceLevel,
			linkViewSourceType,
			linkViewSourceSerial,
			affectedSerials,
			eventMeta
		);
	}

	/**
	 * 发布单条“triggerSource 区块卸载失效”事件。
	 */
	public static void publishTriggerSourceChunkUnloadInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishTriggerSourceChunkUnloadInvalidation(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta
		);
	}

	/**
	 * 发布单条“triggerSource 其它失效”事件。
	 */
	public static void publishTriggerSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishTriggerSourceInvalidation(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta
		);
	}

	/**
	 * 发布单条“sync 来源失效”事件。
	 */
	public static void publishSourceInvalidation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		EventMeta eventMeta
	) {
		InternalDispatchDeltaRuleSupport.publishSourceInvalidation(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerial,
			eventMeta
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
		InternalDispatchDeltaRuleSupport.publishResolvedTargetChunkLoadSyncReplay(
			sourceLevel,
			sourceSerial,
			targetSerial,
			replaySnapshot
		);
	}

	/**
	 * 测试专用：归一化链路视角到 `triggerSource -> core` 序号对。
	 */
	static java.util.Set<SourceTargetPair> normalizeLinkPairsForTesting(
		LinkNodeType linkViewSourceType,
		long linkViewSourceSerial,
		java.util.Set<Long> peerSerials
	) {
		return InternalDispatchDeltaRuleSupport.normalizeLinkPairsForTesting(
			linkViewSourceType,
			linkViewSourceSerial,
			peerSerials
		);
	}

	/**
	 * 测试专用：重置监听器与防重缓存。
	 */
	static void resetForTesting() {
		InternalDispatchDeltaPublishSupport.resetForTesting();
	}

	/**
	 * 归一化后的 `triggerSource -> core` 序号对。
	 */
	record SourceTargetPair(long sourceSerial, long targetSerial) {}

	/**
	 * 记录事件发布去重键；用于同 tick 防重与防环。
	 */
	record DispatchDedupKey(
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
		static DispatchDedupKey from(DispatchDeltaEvent event) {
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
	 * 内部派发 delta 事件快照。
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
		EventMeta eventMeta,
		DeliveryMode deliveryMode
	) {
		public DispatchDeltaEvent {
			activationMode = activationMode == null ? ActivationMode.TOGGLE : activationMode;
			syncSignalStrength = SignalStrengths.clamp(syncSignalStrength);
			eventMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;
			deliveryMode = deliveryMode == null ? DeliveryMode.IMMEDIATE : deliveryMode;
		}

		public DispatchDeltaEvent(
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
			this(
				sourceLevel,
				sourceType,
				sourceSerial,
				targetType,
				targetSerial,
				deltaKind,
				deltaAction,
				activationMode,
				syncSignalStrength,
				eventMeta,
				DeliveryMode.IMMEDIATE
			);
		}
	}
}
