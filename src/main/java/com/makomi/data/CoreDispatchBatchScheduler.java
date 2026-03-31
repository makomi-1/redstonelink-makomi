package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DispatchBatchEntry;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
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
 * 4. tick 末统一 flush 到 `core.applyDispatchBatch(...)`。
 * </p>
 */
public final class CoreDispatchBatchScheduler {
	private static final Map<MinecraftServer, SchedulerState> STATE_BY_SERVER = new IdentityHashMap<>();
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
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATE_BY_SERVER.remove(server));
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
		flushServerBatches(server);
	}

	/**
	 * 测试专用：清理所有服务端状态。
	 */
	static void resetForTesting() {
		STATE_BY_SERVER.clear();
	}

	private static void onEndServerTick(MinecraftServer server) {
		flushServerBatches(server);
	}

	private static void onServerStopping(MinecraftServer server) {
		flushServerBatches(server);
		STATE_BY_SERVER.remove(server);
	}

	private static void flushServerBatches(MinecraftServer server) {
		SchedulerState state = STATE_BY_SERVER.get(server);
		if (state == null || state.pendingByTarget.isEmpty()) {
			return;
		}
		List<TargetBatchAccumulator> pendingSnapshot = new ArrayList<>(state.pendingByTarget.values());
		state.pendingByTarget.clear();
		for (TargetBatchAccumulator accumulator : pendingSnapshot) {
			if (accumulator != null) {
				accumulator.flush();
			}
		}
		if (state.pendingByTarget.isEmpty()) {
			STATE_BY_SERVER.remove(server);
		}
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
		return state.pendingByTarget.computeIfAbsent(targetBatchKey, ignored -> new TargetBatchAccumulator(targetBlockEntity));
	}

	/**
	 * 同一 `core` 的单 tick 聚合缓存。
	 */
	private static final class TargetBatchAccumulator {
		private final ActivatableTargetBlockEntity targetBlockEntity;
		private final LinkedHashMap<SourceDispatchKey, DispatchBatchEntry> entriesBySourceAndKind = new LinkedHashMap<>();

		private TargetBatchAccumulator(ActivatableTargetBlockEntity targetBlockEntity) {
			this.targetBlockEntity = targetBlockEntity;
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
			targetBlockEntity.applyDispatchBatch(List.copyOf(entriesBySourceAndKind.values()));
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
	}
}
