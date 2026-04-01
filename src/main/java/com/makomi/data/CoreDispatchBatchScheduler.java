package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DispatchBatchEntry;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * `core` 目标级批提交调度器。
 * <p>
 * 本调度器仅承接异步 SYNC / invalidation 链路：
 * 1. 生命周期 replay；
 * 2. crosschunk ready release。
 * <p>
 * 调度策略保持轻量：
 * 1. 同一 `core` 在同一 tick 内先聚合；
 * 2. 同源同 kind 仅保留最新条目；
 * 3. `TRIGGER_SOURCE_INVALIDATION` 可覆盖同窗口内更早的 sync / chunk-unload invalidation；
 * 4. 窗口到期时统一 flush 到 `core.applyDispatchBatch(...)`，默认窗口为当前 tick。
 * </p>
 */
public final class CoreDispatchBatchScheduler {
	private static final Map<MinecraftServer, SchedulerState> STATE_BY_SERVER = new IdentityHashMap<>();
	private static final Map<MinecraftServer, Long> LAST_COMPLETED_END_TICK_BY_SERVER = new IdentityHashMap<>();
	private static final int MAX_RETAINED_ACCUMULATORS = 32;
	private static final int MAX_IDLE_TICKS_BEFORE_POOL_RELEASE = 20;
	private static boolean registered;

	private CoreDispatchBatchScheduler() {
	}

	/**
	 * 注册批调度事件。
	 */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerTickEvents.END_SERVER_TICK.register(CoreDispatchBatchScheduler::onEndServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CoreDispatchBatchScheduler::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STATE_BY_SERVER.remove(server);
			LAST_COMPLETED_END_TICK_BY_SERVER.remove(server);
		});
		registered = true;
	}

	/**
	 * 当前批调度器支持的 delta 集合。
	 */
	static boolean supportsBatching(ActivatableTargetBlockEntity.DeltaKind deltaKind) {
		if (deltaKind == null) {
			return false;
		}
		return switch (deltaKind) {
			case SYNC_SIGNAL, SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION, TRIGGER_SOURCE_INVALIDATION -> true;
			case ACTIVATION -> false;
		};
	}

	/**
	 * 将已解析的内部 delta 事件接入异步批调度。
	 */
	static void enqueueLoadedTargetDelta(
		MinecraftServer server,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType targetType,
		long targetSerial,
		InternalDispatchDeltaEvents.DispatchDeltaEvent event
	) {
		if (event == null) {
			return;
		}
		enqueueLoadedTargetDispatch(
			server,
			targetBlockEntity,
			targetType,
			targetSerial,
			event.deltaKind(),
			event.deltaAction(),
			event.sourceType(),
			event.sourceSerial(),
			event.activationMode(),
			event.syncSignalStrength(),
			event.eventMeta()
		);
	}

	/**
	 * 将一条已命中目标实体的异步变更写入 `core` 批次缓存。
	 */
	static void enqueueLoadedTargetDispatch(
		MinecraftServer server,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType targetType,
		long targetSerial,
		ActivatableTargetBlockEntity.DeltaKind deltaKind,
		ActivatableTargetBlockEntity.DeltaAction deltaAction,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		int syncSignalStrength,
		EventMeta eventMeta
	) {
		if (server == null || targetBlockEntity == null || targetType == null || targetSerial <= 0L) {
			return;
		}
		if (deltaAction == null || !supportsBatching(deltaKind) || sourceSerial <= 0L) {
			return;
		}
		TargetBatchAccumulator accumulator = resolveTargetAccumulator(server, targetBlockEntity, targetType, targetSerial);
		if (accumulator == null) {
			return;
		}
		accumulator.merge(
			new DispatchBatchEntry(
				deltaKind,
				deltaAction,
				sourceType,
				sourceSerial,
				activationMode,
				syncSignalStrength,
				eventMeta
			)
		);
	}

	/**
	 * 将同一 `core` 的多条 ready batchable dispatch 一次性写入 scheduler。
	 */
	static boolean enqueueLoadedTargetDispatchBatch(
		MinecraftServer server,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType targetType,
		long targetSerial,
		List<DispatchBatchEntry> batchEntries
	) {
		if (batchEntries == null || batchEntries.isEmpty()) {
			return false;
		}
		TargetBatchAccumulator accumulator = resolveTargetAccumulator(server, targetBlockEntity, targetType, targetSerial);
		if (accumulator == null) {
			return false;
		}
		return accumulator.mergeAll(batchEntries);
	}

	/**
	 * 测试专用：强制 flush 指定服务端的当前批次。
	 */
	static void flushPendingForTesting(MinecraftServer server) {
		flushServerBatches(server, true);
	}

	/**
	 * 测试专用：清理所有服务端状态。
	 */
	static void resetForTesting() {
		STATE_BY_SERVER.clear();
		LAST_COMPLETED_END_TICK_BY_SERVER.clear();
	}

	private static void onEndServerTick(MinecraftServer server) {
		flushServerBatches(server);
		recordCompletedEndTick(server, resolveCurrentTick(server, null));
	}

	private static void onServerStopping(MinecraftServer server) {
		flushServerBatches(server, true);
		STATE_BY_SERVER.remove(server);
		LAST_COMPLETED_END_TICK_BY_SERVER.remove(server);
	}

	/**
	 * 当 `window=0` 且当前 tick 的 `END_SERVER_TICK` 已执行完后，补一次对齐 flush。
	 * <p>
	 * 这样可以覆盖“END 之后、下一 tick 之前”才新入队的 loaded `SYNC`，
	 * 避免它们无谓地拖到下一 tick 末，破坏 `window=0` 的设计目的。
	 * </p>
	 */
	static void flushLateArrivalsIfCurrentTickEndAlreadyPassed(MinecraftServer server, long currentTick) {
		long normalizedCurrentTick = Math.max(0L, currentTick);
		Long lastCompletedEndTick = LAST_COMPLETED_END_TICK_BY_SERVER.get(server);
		if (!shouldFlushLateArrivals(normalizedCurrentTick, configuredBatchWindowTicks(), lastCompletedEndTick)) {
			return;
		}
		flushServerBatches(server);
	}

	/**
	 * 判断当前是否应对 late arrival 触发一次 `window=0` 补 flush。
	 */
	static boolean shouldFlushLateArrivals(long currentTick, int windowTicks, Long lastCompletedEndTick) {
		if (Math.max(0, windowTicks) != 0) {
			return false;
		}
		if (lastCompletedEndTick == null) {
			return false;
		}
		return Math.max(0L, currentTick) == Math.max(0L, lastCompletedEndTick.longValue());
	}

	private static void flushServerBatches(MinecraftServer server) {
		flushServerBatches(server, false);
	}

	private static void flushServerBatches(MinecraftServer server, boolean forceFlush) {
		SchedulerState state = STATE_BY_SERVER.get(server);
		if (state == null) {
			return;
		}
		if (state.pendingByTarget.isEmpty()) {
			if (state.onIdleTickAndShouldRelease()) {
				STATE_BY_SERVER.remove(server);
			}
			return;
		}
		state.markActive();
		int batchWindowTicks = forceFlush ? 0 : configuredBatchWindowTicks();
		List<TargetBatchAccumulator> pendingSnapshot = state.pendingSnapshotScratch;
		pendingSnapshot.clear();
		Iterator<Map.Entry<TargetBatchKey, TargetBatchAccumulator>> iterator = state.pendingByTarget.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<TargetBatchKey, TargetBatchAccumulator> pendingEntry = iterator.next();
			TargetBatchAccumulator accumulator = pendingEntry.getValue();
			if (accumulator == null) {
				iterator.remove();
				continue;
			}
			long currentTick = resolveCurrentTick(server, accumulator.targetBlockEntity);
			if (!forceFlush && !accumulator.isFlushDue(currentTick, batchWindowTicks)) {
				continue;
			}
			pendingSnapshot.add(accumulator);
			iterator.remove();
		}
		for (TargetBatchAccumulator accumulator : pendingSnapshot) {
			if (accumulator == null) {
				continue;
			}
			accumulator.flush();
			state.recycleAccumulator(accumulator);
		}
		pendingSnapshot.clear();
		if (state.pendingByTarget.isEmpty() && state.accumulatorPool.isEmpty()) {
			STATE_BY_SERVER.remove(server);
		}
	}

	private static int configuredBatchWindowTicks() {
		return Math.max(0, RedstoneLinkConfig.crossChunk().dispatchBatchWindowTicks());
	}

	/**
	 * 记录指定服务端最近一次已经完成的 `END_SERVER_TICK`。
	 */
	private static void recordCompletedEndTick(MinecraftServer server, long completedTick) {
		LAST_COMPLETED_END_TICK_BY_SERVER.put(server, Math.max(0L, completedTick));
	}

	private static int compareBatchEntries(DispatchBatchEntry left, DispatchBatchEntry right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return -1;
		}
		if (right == null) {
			return 1;
		}
		int timeKeyCompare = left.eventMeta().timeKey().compareTo(right.eventMeta().timeKey());
		if (timeKeyCompare != 0) {
			return timeKeyCompare;
		}
		int seqCompare = Long.compare(left.eventMeta().seq(), right.eventMeta().seq());
		if (seqCompare != 0) {
			return seqCompare;
		}
		return Integer.compare(dispatchPriority(left.deltaKind()), dispatchPriority(right.deltaKind()));
	}

	private static int dispatchPriority(ActivatableTargetBlockEntity.DeltaKind deltaKind) {
		if (deltaKind == null) {
			return Integer.MAX_VALUE;
		}
		return switch (deltaKind) {
			case SYNC_SIGNAL -> 0;
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION -> 1;
			case TRIGGER_SOURCE_INVALIDATION -> 2;
			case ACTIVATION -> 3;
		};
	}

	private static TargetBatchAccumulator resolveTargetAccumulator(
		MinecraftServer server,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType targetType,
		long targetSerial
	) {
		if (server == null || targetBlockEntity == null || targetType == null || targetSerial <= 0L) {
			return null;
		}
		if (targetBlockEntity.getLevel() == null || targetBlockEntity.getLevel().isClientSide) {
			return null;
		}
		if (targetBlockEntity.getSerial() != targetSerial || targetBlockEntity.getLinkNodeType() != targetType) {
			return null;
		}
		SchedulerState state = STATE_BY_SERVER.computeIfAbsent(server, ignored -> new SchedulerState());
		TargetBatchKey targetBatchKey = new TargetBatchKey(
			targetBlockEntity.getLevel().dimension(),
			targetBlockEntity.getBlockPos().immutable(),
			targetType,
			targetSerial
		);
		state.markActive();
		TargetBatchAccumulator accumulator = state.pendingByTarget.computeIfAbsent(
			targetBatchKey,
			ignored -> state.acquireAccumulator(targetBlockEntity)
		);
		accumulator.openWindowIfNeeded(resolveCurrentTick(server, targetBlockEntity));
		return accumulator;
	}

	private static long resolveCurrentTick(MinecraftServer server, ActivatableTargetBlockEntity targetBlockEntity) {
		if (server != null && server.overworld() != null) {
			return Math.max(0L, server.overworld().getGameTime());
		}
		if (targetBlockEntity != null && targetBlockEntity.getLevel() != null) {
			return Math.max(0L, targetBlockEntity.getLevel().getGameTime());
		}
		return 0L;
	}

	/**
	 * 同一 `core` 的单 tick 聚合缓存。
	 */
	private static final class TargetBatchAccumulator {
		private ActivatableTargetBlockEntity targetBlockEntity;
		private final LinkedHashMap<SourceDispatchKey, DispatchBatchEntry> entriesBySourceAndKind = new LinkedHashMap<>();
		private final List<DispatchBatchEntry> flushEntriesScratch = new ArrayList<>();
		private long windowStartTick = Long.MIN_VALUE;

		private TargetBatchAccumulator(ActivatableTargetBlockEntity targetBlockEntity) {
			this.targetBlockEntity = targetBlockEntity;
		}

		/**
		 * 以新的 target 上下文重置聚合器，复用内部容器。
		 */
		private void reset(ActivatableTargetBlockEntity targetBlockEntity) {
			this.targetBlockEntity = targetBlockEntity;
			entriesBySourceAndKind.clear();
			flushEntriesScratch.clear();
			windowStartTick = Long.MIN_VALUE;
		}

		/**
		 * 目标首次进入 pending 窗口时记录起点，后续 merge 不再刷新。
		 */
		private void openWindowIfNeeded(long currentTick) {
			if (windowStartTick == Long.MIN_VALUE) {
				windowStartTick = Math.max(0L, currentTick);
			}
		}

		/**
		 * 当前目标批次是否已达到可 flush 的窗口。
		 */
		private boolean isFlushDue(long currentTick, int windowTicks) {
			if (entriesBySourceAndKind.isEmpty() || windowTicks <= 0 || windowStartTick == Long.MIN_VALUE) {
				return true;
			}
			long elapsedTicks = Math.max(0L, currentTick - windowStartTick);
			return elapsedTicks >= windowTicks;
		}

		private void merge(DispatchBatchEntry batchEntry) {
			if (batchEntry == null) {
				return;
			}
			SourceDispatchKey sourceDispatchKey = new SourceDispatchKey(
				batchEntry.sourceType(),
				batchEntry.sourceSerial(),
				batchEntry.deltaKind()
			);
			DispatchBatchEntry previous = entriesBySourceAndKind.get(sourceDispatchKey);
			if (previous == null || compareBatchEntries(batchEntry, previous) >= 0) {
				entriesBySourceAndKind.put(sourceDispatchKey, batchEntry);
			}
			if (batchEntry.deltaKind() == ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION) {
				dropCoveredEntries(batchEntry);
			}
		}

		private boolean mergeAll(List<DispatchBatchEntry> batchEntries) {
			if (batchEntries == null || batchEntries.isEmpty()) {
				return false;
			}
			boolean merged = false;
			for (DispatchBatchEntry batchEntry : batchEntries) {
				if (batchEntry == null) {
					continue;
				}
				if (batchEntry.deltaAction() == null || !supportsBatching(batchEntry.deltaKind()) || batchEntry.sourceSerial() <= 0L) {
					continue;
				}
				merge(batchEntry);
				merged = true;
			}
			return merged;
		}

		/**
		 * 完整 invalidation 可以覆盖窗口内更早的 sync / chunk-unload invalidation。
		 */
		private void dropCoveredEntries(DispatchBatchEntry invalidationEntry) {
			Iterator<Map.Entry<SourceDispatchKey, DispatchBatchEntry>> iterator = entriesBySourceAndKind.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<SourceDispatchKey, DispatchBatchEntry> entry = iterator.next();
				DispatchBatchEntry existingEntry = entry.getValue();
				if (existingEntry == null || existingEntry == invalidationEntry) {
					continue;
				}
				if (
					existingEntry.sourceSerial() != invalidationEntry.sourceSerial()
						|| existingEntry.sourceType() != invalidationEntry.sourceType()
				) {
					continue;
				}
				if (
					existingEntry.deltaKind() != ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL
						&& existingEntry.deltaKind() != ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION
						&& existingEntry.deltaKind() != ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION
				) {
					continue;
				}
				if (compareBatchEntries(existingEntry, invalidationEntry) <= 0) {
					iterator.remove();
				}
			}
			entriesBySourceAndKind.put(
				new SourceDispatchKey(
					invalidationEntry.sourceType(),
					invalidationEntry.sourceSerial(),
					invalidationEntry.deltaKind()
				),
				invalidationEntry
			);
		}

		private void flush() {
			if (targetBlockEntity == null || targetBlockEntity.isRemoved()) {
				return;
			}
			if (targetBlockEntity.getLevel() == null || targetBlockEntity.getLevel().isClientSide) {
				return;
			}
			if (entriesBySourceAndKind.isEmpty()) {
				return;
			}
			flushEntriesScratch.clear();
			flushEntriesScratch.addAll(entriesBySourceAndKind.values());
			targetBlockEntity.applyDispatchBatch(flushEntriesScratch);
			entriesBySourceAndKind.clear();
			flushEntriesScratch.clear();
		}

		/**
		 * 回收到对象池前释放 target 引用与残留条目。
		 */
		private void recycle() {
			targetBlockEntity = null;
			entriesBySourceAndKind.clear();
			flushEntriesScratch.clear();
			windowStartTick = Long.MIN_VALUE;
		}
	}

	private record TargetBatchKey(ResourceKey<Level> dimension, BlockPos blockPos, LinkNodeType targetType, long targetSerial) {}

	private record SourceDispatchKey(
		LinkNodeType sourceType,
		long sourceSerial,
		ActivatableTargetBlockEntity.DeltaKind deltaKind
	) {}

	private static final class SchedulerState {
		private final LinkedHashMap<TargetBatchKey, TargetBatchAccumulator> pendingByTarget = new LinkedHashMap<>();
		private final List<TargetBatchAccumulator> pendingSnapshotScratch = new ArrayList<>();
		private final List<TargetBatchAccumulator> accumulatorPool = new ArrayList<>();
		private int idleTicks;

		/**
		 * 当前 tick 存在批调度活动时重置 idle 计数。
		 */
		private void markActive() {
			idleTicks = 0;
		}

		/**
		 * 获取可复用的 target 聚合器。
		 */
		private TargetBatchAccumulator acquireAccumulator(ActivatableTargetBlockEntity targetBlockEntity) {
			int lastIndex = accumulatorPool.size() - 1;
			TargetBatchAccumulator accumulator = lastIndex < 0
				? new TargetBatchAccumulator(targetBlockEntity)
				: accumulatorPool.remove(lastIndex);
			accumulator.reset(targetBlockEntity);
			return accumulator;
		}

		/**
		 * 回收本 tick 已 flush 的 target 聚合器。
		 */
		private void recycleAccumulator(TargetBatchAccumulator accumulator) {
			if (accumulator == null) {
				return;
			}
			accumulator.recycle();
			if (accumulatorPool.size() < MAX_RETAINED_ACCUMULATORS) {
				accumulatorPool.add(accumulator);
			}
		}

		/**
		 * 空闲 tick 内递增 idle 计数；达到阈值后释放 retained pool。
		 */
		private boolean onIdleTickAndShouldRelease() {
			if (!pendingByTarget.isEmpty()) {
				idleTicks = 0;
				return false;
			}
			if (accumulatorPool.isEmpty()) {
				pendingSnapshotScratch.clear();
				idleTicks = 0;
				return true;
			}
			idleTicks++;
			if (idleTicks < MAX_IDLE_TICKS_BEFORE_POOL_RELEASE) {
				return false;
			}
			clearRetainedState();
			return true;
		}

		/**
		 * 释放当前 scheduler state 挂住的复用容器。
		 */
		private void clearRetainedState() {
			pendingByTarget.clear();
			pendingSnapshotScratch.clear();
			accumulatorPool.clear();
			idleTicks = 0;
		}
	}
}
