package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DispatchBatchEntry;
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
 * 频道模式 loaded direct 派发中间收口调度器。
 * <p>
 * 该调度器只服务频道模式下的 loaded direct 路径：
 * 1. 同 tick 内，同频道来源先收口到频道桶；
 * 2. `END_SERVER_TICK` 前统一按目标 `core` 归并；
 * 3. 再根据配置分流到 `CoreDispatchBatchScheduler` 或直接 `applyDispatchBatch(...)`。
 * </p>
 * <p>
 * 当前不接管 pending 队列的 ready drain 路径，也不改普通边存储设计。
 * </p>
 */
public final class ChannelDispatchScheduler {
	private static final Map<MinecraftServer, SchedulerState> STATE_BY_SERVER = new IdentityHashMap<>();
	private static final Map<MinecraftServer, Long> LAST_COMPLETED_END_TICK_BY_SERVER = new IdentityHashMap<>();
	private static boolean registered;

	private ChannelDispatchScheduler() {
	}

	/**
	 * 注册频道中间层调度事件。
	 */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerTickEvents.END_SERVER_TICK.register(ChannelDispatchScheduler::onEndServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(ChannelDispatchScheduler::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STATE_BY_SERVER.remove(server);
			LAST_COMPLETED_END_TICK_BY_SERVER.remove(server);
		});
		registered = true;
	}

	/**
	 * 将一条频道模式 loaded direct 派发写入当前 tick 的频道桶。
	 */
	static void enqueueLoadedChannelDispatch(
		MinecraftServer server,
		long channel,
		long graphRevision,
		List<LoadedChannelTarget> loadedTargets,
		DispatchBatchEntry batchEntry
	) {
		ServerThreadGuard.requireServerThread(server, "channel dispatch scheduler enqueue");
		if (
			server == null
				|| !LinkSavedDataChannelSupport.isValidChannel(channel)
				|| loadedTargets == null
				|| loadedTargets.isEmpty()
				|| batchEntry == null
		) {
			return;
		}
		long eventTick = Math.max(0L, batchEntry.eventMeta().timeKey().tick());
		SchedulerState state = STATE_BY_SERVER.computeIfAbsent(server, ignored -> new SchedulerState());
		state.markActive();
		ChannelBucketKey channelBucketKey = new ChannelBucketKey(eventTick, Math.max(0L, graphRevision), channel);
		ChannelBucket channelBucket = state.pendingByChannel.computeIfAbsent(channelBucketKey, ignored -> new ChannelBucket());
		channelBucket.merge(loadedTargets, batchEntry);
	}

	/**
	 * `window=0` 时，若当前 tick 的 `END_SERVER_TICK` 已结束，补一次同 tick flush。
	 */
	static void flushLateArrivalsIfCurrentTickEndAlreadyPassed(MinecraftServer server, long currentTick) {
		ServerThreadGuard.requireServerThread(server, "channel dispatch scheduler late-arrival flush");
		long normalizedCurrentTick = Math.max(0L, currentTick);
		Long lastCompletedEndTick = LAST_COMPLETED_END_TICK_BY_SERVER.get(server);
		if (!shouldFlushLateArrivals(normalizedCurrentTick, lastCompletedEndTick)) {
			return;
		}
		flushServerBuckets(server, true);
	}

	/**
	 * 测试专用：强制 flush 当前服务端全部频道桶。
	 */
	static void flushPendingForTesting(MinecraftServer server) {
		flushServerBuckets(server, true);
	}

	/**
	 * 测试专用：清空内部状态。
	 */
	static void resetForTesting() {
		STATE_BY_SERVER.clear();
		LAST_COMPLETED_END_TICK_BY_SERVER.clear();
	}

	private static void onEndServerTick(MinecraftServer server) {
		ServerThreadGuard.requireServerThread(server, "channel dispatch scheduler tick");
		flushServerBuckets(server, false);
		recordCompletedEndTick(server, resolveCurrentTick(server));
	}

	private static void onServerStopping(MinecraftServer server) {
		ServerThreadGuard.requireServerThread(server, "channel dispatch scheduler stop");
		flushServerBuckets(server, true);
		STATE_BY_SERVER.remove(server);
		LAST_COMPLETED_END_TICK_BY_SERVER.remove(server);
	}

	private static boolean shouldFlushLateArrivals(long currentTick, Long lastCompletedEndTick) {
		if (lastCompletedEndTick == null) {
			return false;
		}
		return Math.max(0L, currentTick) == Math.max(0L, lastCompletedEndTick.longValue());
	}

	private static void flushServerBuckets(MinecraftServer server, boolean forceFlush) {
		ServerThreadGuard.requireServerThread(server, "channel dispatch scheduler flush");
		SchedulerState state = STATE_BY_SERVER.get(server);
		if (state == null) {
			return;
		}
		long currentTick = resolveCurrentTick(server);
		boolean enqueuedCoreBatch = false;
		Iterator<Map.Entry<ChannelBucketKey, ChannelBucket>> iterator = state.pendingByChannel.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ChannelBucketKey, ChannelBucket> pendingEntry = iterator.next();
			ChannelBucketKey bucketKey = pendingEntry.getKey();
			ChannelBucket bucket = pendingEntry.getValue();
			if (bucketKey == null || bucket == null) {
				iterator.remove();
				continue;
			}
			if (!forceFlush && bucketKey.tick() > currentTick) {
				continue;
			}
			enqueuedCoreBatch |= bucket.flush(server);
			iterator.remove();
		}
		if (state.pendingByChannel.isEmpty()) {
			STATE_BY_SERVER.remove(server);
		}
		if (enqueuedCoreBatch) {
			CoreDispatchBatchScheduler.flushLateArrivalsIfCurrentTickEndAlreadyPassed(server, currentTick);
		}
	}

	private static void recordCompletedEndTick(MinecraftServer server, long completedTick) {
		LAST_COMPLETED_END_TICK_BY_SERVER.put(server, Math.max(0L, completedTick));
	}

	private static long resolveCurrentTick(MinecraftServer server) {
		if (server != null) {
			try {
				if (server.overworld() != null) {
					return Math.max(0L, server.overworld().getGameTime());
				}
			} catch (RuntimeException ignored) {
				// 测试中的 dummy server 可能未完整初始化；此时回退到 0 tick 即可。
			}
		}
		return 0L;
	}

	/**
	 * 一条已分类为“loaded direct 且允许进入频道收口层”的目标引用。
	 */
	static record LoadedChannelTarget(
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType targetType,
		long targetSerial
	) {}

	private record ChannelBucketKey(long tick, long graphRevision, long channel) {}

	private record ChannelTargetKey(
		ResourceKey<Level> dimension,
		BlockPos blockPos,
		LinkNodeType targetType,
		long targetSerial
	) {
		private static ChannelTargetKey of(ActivatableTargetBlockEntity targetBlockEntity, LinkNodeType targetType, long targetSerial) {
			if (targetBlockEntity == null || targetBlockEntity.getLevel() == null || targetType == null || targetSerial <= 0L) {
				return null;
			}
			return new ChannelTargetKey(
				targetBlockEntity.getLevel().dimension(),
				targetBlockEntity.getBlockPos().immutable(),
				targetType,
				targetSerial
			);
		}
	}

	private static final class SchedulerState {
		private final LinkedHashMap<ChannelBucketKey, ChannelBucket> pendingByChannel = new LinkedHashMap<>();

		private void markActive() {
			// 当前版本只需保留钩子，便于后续引入 retained pool / 统计。
		}
	}

	/**
	 * 单个频道桶；同 tick 内同频道来源统一写入这里。
	 */
	private static final class ChannelBucket {
		private final LinkedHashMap<ChannelTargetKey, ChannelTargetAccumulator> accumulatorsByTarget = new LinkedHashMap<>();

		private void merge(List<LoadedChannelTarget> loadedTargets, DispatchBatchEntry batchEntry) {
			if (loadedTargets == null || loadedTargets.isEmpty() || batchEntry == null) {
				return;
			}
			for (LoadedChannelTarget loadedTarget : loadedTargets) {
				if (
					loadedTarget == null
						|| loadedTarget.targetBlockEntity() == null
						|| loadedTarget.targetType() == null
						|| loadedTarget.targetSerial() <= 0L
				) {
					continue;
				}
				ChannelTargetKey channelTargetKey = ChannelTargetKey.of(
					loadedTarget.targetBlockEntity(),
					loadedTarget.targetType(),
					loadedTarget.targetSerial()
				);
				if (channelTargetKey == null) {
					continue;
				}
				ChannelTargetAccumulator accumulator = accumulatorsByTarget.computeIfAbsent(
					channelTargetKey,
					ignored -> new ChannelTargetAccumulator(
						loadedTarget.targetBlockEntity(),
						loadedTarget.targetType(),
						loadedTarget.targetSerial()
					)
				);
				accumulator.add(batchEntry);
			}
		}

		private boolean flush(MinecraftServer server) {
			boolean enqueuedCoreBatch = false;
			for (ChannelTargetAccumulator accumulator : accumulatorsByTarget.values()) {
				if (accumulator != null) {
					enqueuedCoreBatch |= accumulator.flush(server);
				}
			}
			accumulatorsByTarget.clear();
			return enqueuedCoreBatch;
		}
	}

	/**
	 * 单个目标 core 在频道桶中的累计条目。
	 */
	private static final class ChannelTargetAccumulator {
		private final ActivatableTargetBlockEntity targetBlockEntity;
		private final LinkNodeType targetType;
		private final long targetSerial;
		private final List<DispatchBatchEntry> stagedEntries = new ArrayList<>();
		private final List<DispatchBatchEntry> batchableScratch = new ArrayList<>();
		private final List<DispatchBatchEntry> directScratch = new ArrayList<>();

		private ChannelTargetAccumulator(
			ActivatableTargetBlockEntity targetBlockEntity,
			LinkNodeType targetType,
			long targetSerial
		) {
			this.targetBlockEntity = targetBlockEntity;
			this.targetType = targetType;
			this.targetSerial = targetSerial;
		}

		private void add(DispatchBatchEntry batchEntry) {
			if (batchEntry != null) {
				stagedEntries.add(batchEntry);
			}
		}

		private boolean flush(MinecraftServer server) {
			if (
				server == null
					|| targetBlockEntity == null
					|| targetBlockEntity.isRemoved()
					|| targetBlockEntity.getLevel() == null
					|| targetBlockEntity.getLevel().isClientSide
					|| targetBlockEntity.getSerial() != targetSerial
					|| targetBlockEntity.getLinkNodeType() != targetType
					|| stagedEntries.isEmpty()
			) {
				stagedEntries.clear();
				batchableScratch.clear();
				directScratch.clear();
				return false;
			}
			batchableScratch.clear();
			directScratch.clear();
			for (DispatchBatchEntry batchEntry : stagedEntries) {
				if (batchEntry == null) {
					continue;
				}
				if (shouldGoToCoreBatch(batchEntry)) {
					batchableScratch.add(batchEntry);
				} else {
					directScratch.add(batchEntry);
				}
			}
			if (!directScratch.isEmpty()) {
				targetBlockEntity.applyDispatchBatch(directScratch);
			}
			boolean enqueuedCoreBatch = false;
			if (!batchableScratch.isEmpty()) {
				enqueuedCoreBatch = CoreDispatchBatchScheduler.enqueueLoadedTargetDispatchBatch(
					server,
					targetBlockEntity,
					targetType,
					targetSerial,
					batchableScratch
				);
			}
			stagedEntries.clear();
			batchableScratch.clear();
			directScratch.clear();
			return enqueuedCoreBatch;
		}

		/**
		 * 判断一条频道桶内条目最终是否仍应交给 core 目标级 batch。
		 * <p>
		 * 频道中间层始终存在；这里只决定“中间层 flush 后”是继续走 core batch，还是直接一次性落到目标。
		 * </p>
		 */
		private static boolean shouldGoToCoreBatch(DispatchBatchEntry batchEntry) {
			if (batchEntry == null || batchEntry.deltaKind() == null) {
				return false;
			}
			RedstoneLinkConfig.CrossChunkDirectBatchingMode directBatchingMode =
				RedstoneLinkConfig.crossChunk().directBatchingMode();
			return switch (batchEntry.deltaKind()) {
				case ACTIVATION -> directBatchingMode == RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT;
				case SYNC_SIGNAL -> InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
					ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
					InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
					directBatchingMode
				);
				case SOURCE_INVALIDATION,
					TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
					TRIGGER_SOURCE_INVALIDATION -> InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
					batchEntry.deltaKind(),
					InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
					directBatchingMode
				);
			};
		}
	}
}
