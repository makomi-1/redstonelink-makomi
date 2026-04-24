package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DispatchBatchEntry;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 跨区块派发运行时 helper。
 * <p>
 * 负责 pending 刷队列、重试退避、目标区块加载唤醒和运行态重试索引维护。
 * </p>
 */
final class CrossChunkDispatchRuntimeSupport {
	private static final int MAX_RETAINED_READY_GROUP_MAPS = 32;
	private static final int MAX_RETAINED_READY_GROUPS = 64;

	private CrossChunkDispatchRuntimeSupport() {
	}

	/**
	 * 处理当前 tick 的 pending 刷队列循环。
	 */
	static void processPendingDispatches(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		long gameTime
	) {
		if (queueData == null) {
			clearTransientRuntimeState(state);
			return;
		}
		queueData.purgeExpired(gameTime);
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> snapshot = queueData.pendingEntriesSnapshot();
		if (snapshot.isEmpty()) {
			clearRetryTracking(state);
			clearTransientRuntimeState(state);
			state.pendingCursor = 0L;
			return;
		}
		pruneRetryStateBySnapshot(state, snapshot);

		int budget = Math.max(1, RedstoneLinkConfig.crossChunk().dispatchMaxPerTick());
		int snapshotSize = snapshot.size();
		int startIndex = Math.floorMod(state.pendingCursor, snapshotSize);
		int processed = 0;
		int visited = 0;
		TargetLocatorCache targetLocatorCache = state.targetLocatorCache;
		ChunkReadyDrainCache readyDrainCache = state.readyDrainCache;
		targetLocatorCache.reset();
		readyDrainCache.reset();
		while (visited < snapshotSize && processed < budget) {
			int currentIndex = (startIndex + visited) % snapshotSize;
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = snapshot.get(currentIndex);
			visited++;
			processed++;
			if (pending.expireGameTick() <= gameTime) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
				continue;
			}
			if (queueData.isStaleByAcceptedVersion(pending.key(), pending.version())) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
				continue;
			}
			if (shouldDeferRetryUntilEligible(state, pending, gameTime)) {
				continue;
			}
			DispatchAttemptResult dispatchAttemptResult = tryDispatch(
				server,
				state,
				queueData,
				pending,
				gameTime,
				targetLocatorCache,
				readyDrainCache
			);
			if (dispatchAttemptResult == DispatchAttemptResult.ACCEPTED) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
				continue;
			}
			if (dispatchAttemptResult == DispatchAttemptResult.READY_STAGED) {
				continue;
			}
			if (recordRetryFailureAndShouldDrop(state, pending, gameTime)) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
			}
		}
		readyDrainCache.drainToBatchScheduler(server, queueData, acceptedPending -> {
			queueData.removePending(acceptedPending.key());
			clearRetryState(state, acceptedPending);
		});
		if (queueData.pendingSize() <= 0) {
			clearRetryTracking(state);
			clearTransientRuntimeState(state);
			state.pendingCursor = 0L;
			return;
		}
		state.pendingCursor = state.pendingCursor + processed;
	}

	/**
	 * 在 pending 刷队列完全空闲时释放仅用于临时调度的 retained 容器。
	 */
	static void clearTransientRuntimeState(CrossChunkDispatchService.DispatchState state) {
		if (state == null) {
			return;
		}
		state.retryActiveKeysScratch.clear();
		state.wakeAttemptSnapshotScratch.clear();
		state.targetLocatorCache.clearRetainedState();
		state.readyDrainCache.clearRetainedState();
	}

	/**
	 * 尝试将单条 pending 投递到已加载目标。
	 */
	static DispatchAttemptResult tryDispatch(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime,
		TargetLocatorCache targetLocatorCache,
		ChunkReadyDrainCache readyDrainCache
	) {
		if (queueData.isStaleByAcceptedVersion(pending.key(), pending.version())) {
			return DispatchAttemptResult.ACCEPTED;
		}
		TargetLocatorResult locatorResult = targetLocatorCache.locate(server, pending);
		if (locatorResult.status() == TargetLocatorStatus.RETRYABLE_MISS) {
			if (locatorResult.shouldAttemptForceLoad() && CrossChunkDispatchService.shouldForceLoad(locatorResult.targetLevel(), pending)) {
				CrossChunkDispatchService.tryForceLoad(server, state, pending, gameTime);
			}
			return DispatchAttemptResult.RETRYABLE_MISS;
		}
		if (locatorResult.status() == TargetLocatorStatus.INVALID_TARGET) {
			if (locatorResult.targetLevel() != null) {
				LinkSavedData.get(locatorResult.targetLevel()).removeNode(pending.key().targetType(), pending.key().targetSerial());
			}
			return DispatchAttemptResult.ACCEPTED;
		}

		PreparedDispatch preparedDispatch = prepareDispatch(pending, locatorResult.targetBlockEntity());
		if (preparedDispatch == null) {
			return DispatchAttemptResult.RETRYABLE_MISS;
		}
		if (preparedDispatch.supportsBatching()) {
			readyDrainCache.stageBatchable(preparedDispatch);
			return DispatchAttemptResult.READY_STAGED;
		}
		preparedDispatch.applyDirect();
		queueData.markAccepted(pending.key(), pending.version());
		return DispatchAttemptResult.ACCEPTED;
	}

	/**
	 * 将 pending 规约为可直接应用或可进入 batch 的内部派发项。
	 */
	static PreparedDispatch prepareDispatch(
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		ActivatableTargetBlockEntity targetBlockEntity
	) {
		if (pending == null || pending.key() == null || targetBlockEntity == null) {
			return null;
		}
		ActivatableTargetBlockEntity.DeltaKind deltaKind = switch (pending.key().dispatchKind()) {
			case PULSE_EVENT, TOGGLE_EVENT -> ActivatableTargetBlockEntity.DeltaKind.ACTIVATION;
			case SYNC_SIGNAL -> ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL;
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION ->
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION;
			case TRIGGER_SOURCE_INVALIDATION -> ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION;
		};
		ActivatableTargetBlockEntity.DeltaAction deltaAction = pending.dispatchAction() == CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE
			? ActivatableTargetBlockEntity.DeltaAction.REMOVE
			: ActivatableTargetBlockEntity.DeltaAction.UPSERT;
		EventMeta eventMeta = EventMeta.of(pending.enqueueGameTick(), pending.enqueueGameSlot(), pending.version());
		return new PreparedDispatch(pending, targetBlockEntity, deltaKind, deltaAction, eventMeta);
	}

	/**
	 * 当前 pending 的尝试结果。
	 */
	enum DispatchAttemptResult {
		ACCEPTED,
		READY_STAGED,
		RETRYABLE_MISS
	}

	/**
	 * ready 目标的内部派发项。
	 */
	record PreparedDispatch(
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		ActivatableTargetBlockEntity targetBlockEntity,
		ActivatableTargetBlockEntity.DeltaKind deltaKind,
		ActivatableTargetBlockEntity.DeltaAction deltaAction,
		EventMeta eventMeta
	) {
		PreparedDispatch {
			eventMeta = eventMeta == null ? EventMeta.of(0L, 0, 0L) : eventMeta;
		}

		/**
		 * 当前条目是否允许进入 `core` 批调度。
		 */
		boolean supportsBatching() {
			return CoreDispatchBatchScheduler.supportsBatching(deltaKind);
		}

		/**
		 * 转换为 `core` 批调度使用的 batch entry。
		 */
		DispatchBatchEntry toBatchEntry() {
			return new DispatchBatchEntry(
				deltaKind,
				deltaAction,
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.activationMode(),
				pending.syncSignalStrength(),
				eventMeta
			);
		}

		/**
		 * 保持 direct 语义的 ready 派发。
		 */
		void applyDirect() {
			targetBlockEntity.applyDispatchDelta(
				deltaKind,
				deltaAction,
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.activationMode(),
				pending.syncSignalStrength(),
				eventMeta
			);
		}
	}

	/**
	 * 目标定位缓存键：同 tick 内同一 `core` 只解析一次。
	 */
	private record TargetLocatorKey(ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos, LinkNodeType targetType, long targetSerial) {
		private static TargetLocatorKey of(CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending) {
			if (pending == null || pending.key() == null || pending.dimension() == null || pending.pos() == null) {
				return null;
			}
			return new TargetLocatorKey(
				pending.dimension(),
				pending.pos().immutable(),
				pending.key().targetType(),
				pending.key().targetSerial()
			);
		}
	}

	/**
	 * 目标定位三态。
	 */
	enum TargetLocatorStatus {
		READY_TARGET,
		RETRYABLE_MISS,
		INVALID_TARGET
	}

	/**
	 * 目标定位结果。
	 */
	record TargetLocatorResult(
		TargetLocatorStatus status,
		ServerLevel targetLevel,
		ActivatableTargetBlockEntity targetBlockEntity,
		boolean shouldAttemptForceLoad
	) {
		private static TargetLocatorResult ready(ServerLevel targetLevel, ActivatableTargetBlockEntity targetBlockEntity) {
			return new TargetLocatorResult(TargetLocatorStatus.READY_TARGET, targetLevel, targetBlockEntity, false);
		}

		private static TargetLocatorResult retryable(ServerLevel targetLevel, boolean shouldAttemptForceLoad) {
			return new TargetLocatorResult(TargetLocatorStatus.RETRYABLE_MISS, targetLevel, null, shouldAttemptForceLoad);
		}

		private static TargetLocatorResult invalid(ServerLevel targetLevel) {
			return new TargetLocatorResult(TargetLocatorStatus.INVALID_TARGET, targetLevel, null, false);
		}
	}

	/**
	 * 同 tick 内的目标定位短缓存。
	 */
	static final class TargetLocatorCache {
		private final LinkedHashMap<TargetLocatorKey, TargetLocatorResult> locatorResults = new LinkedHashMap<>();

		/**
		 * 进入新一轮 tick 处理前清空定位缓存，但复用内部 map 容器。
		 */
		void reset() {
			locatorResults.clear();
		}

		/**
		 * 在运行态完全空闲时释放 retained locator 缓存。
		 */
		void clearRetainedState() {
			locatorResults.clear();
		}

		/**
		 * 定位 pending 当前指向的目标实体。
		 */
		TargetLocatorResult locate(MinecraftServer server, CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending) {
			TargetLocatorKey locatorKey = TargetLocatorKey.of(pending);
			if (locatorKey == null) {
				return TargetLocatorResult.retryable(null, false);
			}
			return locatorResults.computeIfAbsent(locatorKey, ignored -> resolveTarget(server, pending));
		}

		/**
		 * 暴露缓存大小，便于单元测试验证是否发生复用。
		 */
		int size() {
			return locatorResults.size();
		}

		private static TargetLocatorResult resolveTarget(
			MinecraftServer server,
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
		) {
			if (server == null || pending == null || pending.key() == null || pending.dimension() == null || pending.pos() == null) {
				return TargetLocatorResult.retryable(null, false);
			}
			ServerLevel targetLevel = server.getLevel(pending.dimension());
			if (targetLevel == null) {
				return TargetLocatorResult.retryable(null, false);
			}
			if (!targetLevel.isLoaded(pending.pos())) {
				return TargetLocatorResult.retryable(targetLevel, true);
			}

			LevelChunk targetChunk = targetLevel.getChunkSource().getChunkNow(pending.pos().getX() >> 4, pending.pos().getZ() >> 4);
			if (targetChunk == null) {
				return TargetLocatorResult.retryable(targetLevel, false);
			}
			BlockEntity blockEntity = targetChunk.getBlockEntity(pending.pos(), LevelChunk.EntityCreationType.CHECK);
			if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
				if (blockEntity == null && targetChunk.getBlockState(pending.pos()).hasBlockEntity()) {
					return TargetLocatorResult.retryable(targetLevel, false);
				}
				return TargetLocatorResult.invalid(targetLevel);
			}
			if (!targetBlockEntity.matchesNodeIdentity(pending.key().targetType(), pending.key().targetSerial())) {
				return TargetLocatorResult.invalid(targetLevel);
			}
			return TargetLocatorResult.ready(targetLevel, targetBlockEntity);
		}
	}

	/**
	 * ready batchable pending 的 chunk/core 分组缓存。
	 */
	static final class ChunkReadyDrainCache {
		private final LinkedHashMap<
			CrossChunkDispatchService.TargetChunkKey,
			LinkedHashMap<ReadyDrainTargetKey, ReadyDrainTargetGroup>
		> readyGroupsByChunk = new LinkedHashMap<>();
		private final List<LinkedHashMap<ReadyDrainTargetKey, ReadyDrainTargetGroup>> readyGroupMapPool = new ArrayList<>();
		private final List<ReadyDrainTargetGroup> readyGroupPool = new ArrayList<>();

		/**
		 * 进入新一轮 tick 处理前重置缓存状态，但复用内部容器对象。
		 */
		void reset() {
			recycleAllGroups();
			trimRetainedPools();
		}

		/**
		 * 将 ready 的 batchable 派发项写入 drain 缓存。
		 */
		void stageBatchable(PreparedDispatch preparedDispatch) {
			if (preparedDispatch == null || !preparedDispatch.supportsBatching()) {
				return;
			}
			CrossChunkDispatchService.TargetChunkKey targetChunkKey = targetChunkKeyOf(preparedDispatch.pending());
			ReadyDrainTargetKey readyDrainTargetKey = ReadyDrainTargetKey.of(preparedDispatch.pending());
			if (targetChunkKey == null || readyDrainTargetKey == null) {
				return;
			}
			LinkedHashMap<ReadyDrainTargetKey, ReadyDrainTargetGroup> groupsByTarget = readyGroupsByChunk.get(targetChunkKey);
			if (groupsByTarget == null) {
				groupsByTarget = acquireReadyGroupMap();
				readyGroupsByChunk.put(targetChunkKey, groupsByTarget);
			}
			ReadyDrainTargetGroup readyDrainTargetGroup = groupsByTarget.get(readyDrainTargetKey);
			if (readyDrainTargetGroup == null) {
				readyDrainTargetGroup = acquireReadyGroup(preparedDispatch.targetBlockEntity(), readyDrainTargetKey);
				groupsByTarget.put(readyDrainTargetKey, readyDrainTargetGroup);
			}
			readyDrainTargetGroup.add(preparedDispatch);
		}

		/**
		 * 将当前 tick 命中的 ready batchable pending 统一写入 batch scheduler。
		 */
		void drainToBatchScheduler(
			MinecraftServer server,
			CrossChunkDispatchQueueSavedData queueData,
			java.util.function.Consumer<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> acceptedConsumer
		) {
			if (readyGroupsByChunk.isEmpty()) {
				return;
			}
			try {
				if (server == null || queueData == null) {
					return;
				}
				for (Map<ReadyDrainTargetKey, ReadyDrainTargetGroup> groupsByTarget : readyGroupsByChunk.values()) {
					for (ReadyDrainTargetGroup readyDrainTargetGroup : groupsByTarget.values()) {
						readyDrainTargetGroup.drain(server, queueData, acceptedConsumer);
					}
				}
			} finally {
				recycleAllGroups();
			}
		}

		/**
		 * 暴露 ready chunk 桶数量，便于测试验证按 chunk 聚合。
		 */
		int chunkBucketCount() {
			return readyGroupsByChunk.size();
		}

		/**
		 * 暴露 ready target 分组数量，便于测试验证按 core 聚合。
		 */
		int targetGroupCount() {
			int count = 0;
			for (Map<ReadyDrainTargetKey, ReadyDrainTargetGroup> groupsByTarget : readyGroupsByChunk.values()) {
				count += groupsByTarget.size();
			}
			return count;
		}

		/**
		 * 获取可复用的 target->group 映射容器。
		 */
		private LinkedHashMap<ReadyDrainTargetKey, ReadyDrainTargetGroup> acquireReadyGroupMap() {
			int lastIndex = readyGroupMapPool.size() - 1;
			if (lastIndex < 0) {
				return new LinkedHashMap<>();
			}
			return readyGroupMapPool.remove(lastIndex);
		}

		/**
		 * 获取可复用的单 target 分组容器。
		 */
		private ReadyDrainTargetGroup acquireReadyGroup(
			ActivatableTargetBlockEntity targetBlockEntity,
			ReadyDrainTargetKey readyDrainTargetKey
		) {
			int lastIndex = readyGroupPool.size() - 1;
			ReadyDrainTargetGroup readyDrainTargetGroup = lastIndex < 0
				? new ReadyDrainTargetGroup()
				: readyGroupPool.remove(lastIndex);
			readyDrainTargetGroup.resetForStage(targetBlockEntity, readyDrainTargetKey);
			return readyDrainTargetGroup;
		}

		/**
		 * 回收本 tick 内已使用的 chunk/target 分组容器。
		 */
		private void recycleAllGroups() {
			if (readyGroupsByChunk.isEmpty()) {
				return;
			}
			for (LinkedHashMap<ReadyDrainTargetKey, ReadyDrainTargetGroup> groupsByTarget : readyGroupsByChunk.values()) {
				for (ReadyDrainTargetGroup readyDrainTargetGroup : groupsByTarget.values()) {
					readyDrainTargetGroup.recycle();
					if (readyGroupPool.size() < MAX_RETAINED_READY_GROUPS) {
						readyGroupPool.add(readyDrainTargetGroup);
					}
				}
				groupsByTarget.clear();
				if (readyGroupMapPool.size() < MAX_RETAINED_READY_GROUP_MAPS) {
					readyGroupMapPool.add(groupsByTarget);
				}
			}
			readyGroupsByChunk.clear();
			trimRetainedPools();
		}

		/**
		 * 在跨区块调度完全空闲时释放 retained ready-drain 池。
		 */
		void clearRetainedState() {
			recycleAllGroups();
			readyGroupMapPool.clear();
			readyGroupPool.clear();
		}

		/**
		 * 对 retained pool 做一次上限裁剪，避免历史峰值长期驻留。
		 */
		private void trimRetainedPools() {
			while (readyGroupMapPool.size() > MAX_RETAINED_READY_GROUP_MAPS) {
				readyGroupMapPool.remove(readyGroupMapPool.size() - 1);
			}
			while (readyGroupPool.size() > MAX_RETAINED_READY_GROUPS) {
				readyGroupPool.remove(readyGroupPool.size() - 1);
			}
		}
	}

	/**
	 * ready drain 的目标分组键。
	 */
	private record ReadyDrainTargetKey(ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos, LinkNodeType targetType, long targetSerial) {
		private static ReadyDrainTargetKey of(CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending) {
			if (pending == null || pending.key() == null || pending.dimension() == null || pending.pos() == null) {
				return null;
			}
			return new ReadyDrainTargetKey(
				pending.dimension(),
				pending.pos().immutable(),
				pending.key().targetType(),
				pending.key().targetSerial()
			);
		}
	}

	/**
	 * 单个 `core` 的 ready drain 分组。
	 */
	private static final class ReadyDrainTargetGroup {
		private ActivatableTargetBlockEntity targetBlockEntity;
		private ReadyDrainTargetKey readyDrainTargetKey;
		private final List<PreparedDispatch> stagedBatchableDispatches = new ArrayList<>();
		private final List<DispatchBatchEntry> batchEntriesScratch = new ArrayList<>();

		/**
		 * 以新的 target 信息重置分组，复用内部 list 容器。
		 */
		private void resetForStage(
			ActivatableTargetBlockEntity targetBlockEntity,
			ReadyDrainTargetKey readyDrainTargetKey
		) {
			this.targetBlockEntity = targetBlockEntity;
			this.readyDrainTargetKey = readyDrainTargetKey;
			stagedBatchableDispatches.clear();
			batchEntriesScratch.clear();
		}

		private void add(PreparedDispatch preparedDispatch) {
			if (preparedDispatch != null) {
				stagedBatchableDispatches.add(preparedDispatch);
			}
		}

		private void drain(
			MinecraftServer server,
			CrossChunkDispatchQueueSavedData queueData,
			java.util.function.Consumer<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> acceptedConsumer
		) {
			if (
				server == null
					|| queueData == null
					|| targetBlockEntity == null
					|| targetBlockEntity.isRemoved()
					|| targetBlockEntity.getLevel() == null
					|| targetBlockEntity.getLevel().isClientSide
					|| !targetBlockEntity.matchesNodeIdentity(
						readyDrainTargetKey.targetType(),
						readyDrainTargetKey.targetSerial()
					)
					|| stagedBatchableDispatches.isEmpty()
			) {
				return;
			}
			batchEntriesScratch.clear();
			for (PreparedDispatch preparedDispatch : stagedBatchableDispatches) {
				batchEntriesScratch.add(preparedDispatch.toBatchEntry());
			}
			if (
				!CoreDispatchBatchScheduler.enqueueLoadedTargetDispatchBatch(
					server,
					targetBlockEntity,
					readyDrainTargetKey.targetType(),
					readyDrainTargetKey.targetSerial(),
					batchEntriesScratch
				)
			) {
				return;
			}
			for (PreparedDispatch preparedDispatch : stagedBatchableDispatches) {
				CrossChunkDispatchQueueSavedData.PendingDispatchEntry acceptedPending = preparedDispatch.pending();
				queueData.markAccepted(acceptedPending.key(), acceptedPending.version());
				if (acceptedConsumer != null) {
					acceptedConsumer.accept(acceptedPending);
				}
			}
		}

		/**
		 * 回收到对象池前清理 target 引用与 staged 数据。
		 */
		private void recycle() {
			targetBlockEntity = null;
			readyDrainTargetKey = null;
			stagedBatchableDispatches.clear();
			batchEntriesScratch.clear();
		}
	}

	/**
	 * 清理已脱离当前 pending 快照的重试状态。
	 */
	static void pruneRetryStateBySnapshot(
		CrossChunkDispatchService.DispatchState state,
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> snapshot
	) {
		if (state == null || state.retryStateByAttemptKey.isEmpty()) {
			return;
		}
		if (snapshot == null || snapshot.isEmpty()) {
			clearRetryTracking(state);
			return;
		}
		Set<CrossChunkDispatchService.PendingAttemptKey> activeKeys = state.retryActiveKeysScratch;
		activeKeys.clear();
		for (CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending : snapshot) {
			if (pending == null || pending.key() == null) {
				continue;
			}
			activeKeys.add(new CrossChunkDispatchService.PendingAttemptKey(pending.key(), pending.version()));
		}
		Iterator<Map.Entry<CrossChunkDispatchService.PendingAttemptKey, CrossChunkDispatchService.RetryState>> iterator =
			state.retryStateByAttemptKey.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<CrossChunkDispatchService.PendingAttemptKey, CrossChunkDispatchService.RetryState> entry = iterator.next();
			if (activeKeys.contains(entry.getKey())) {
				continue;
			}
			removePendingAttemptFromWakeIndex(state, entry.getKey(), entry.getValue());
			iterator.remove();
		}
		activeKeys.clear();
	}

	/**
	 * 清理单条 pending 的重试状态与唤醒索引。
	 */
	static void clearRetryState(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		if (state == null || pending == null || pending.key() == null) {
			return;
		}
		CrossChunkDispatchService.PendingAttemptKey attemptKey =
			new CrossChunkDispatchService.PendingAttemptKey(pending.key(), pending.version());
		CrossChunkDispatchService.RetryState retryState = state.retryStateByAttemptKey.remove(attemptKey);
		removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
	}

	/**
	 * 不限时 pending 未到可重试 tick 时继续延后。
	 */
	static boolean shouldDeferRetryUntilEligible(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime
	) {
		if (state == null || pending == null || !isUnlimitedPending(pending)) {
			return false;
		}
		CrossChunkDispatchService.PendingAttemptKey attemptKey =
			new CrossChunkDispatchService.PendingAttemptKey(pending.key(), pending.version());
		CrossChunkDispatchService.RetryState retryState = state.retryStateByAttemptKey.get(attemptKey);
		if (retryState == null) {
			return false;
		}
		if (retryState.nextEligibleTick <= gameTime) {
			removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
			return false;
		}
		return retryState.nextEligibleTick > gameTime;
	}

	/**
	 * 记录一次失败并根据配置决定是否丢弃该 pending。
	 */
	static boolean recordRetryFailureAndShouldDrop(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime
	) {
		if (state == null || pending == null || pending.key() == null) {
			return false;
		}
		CrossChunkDispatchService.PendingAttemptKey attemptKey =
			new CrossChunkDispatchService.PendingAttemptKey(pending.key(), pending.version());
		CrossChunkDispatchService.RetryState retryState =
			state.retryStateByAttemptKey.computeIfAbsent(attemptKey, ignored -> new CrossChunkDispatchService.RetryState());
		retryState.attempts++;
		boolean unlimitedPending = isUnlimitedPending(pending);
		if (unlimitedPending) {
			retryState.nextEligibleTick = computeNextEligibleTick(
				gameTime,
				resolvePersistentRetryIntervalTicks(retryState.attempts)
			);
			indexWaitingUnlimitedPending(state, attemptKey, retryState, pending);
		} else {
			removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
		}

		int warnThreshold = RedstoneLinkConfig.crossChunk().retry().warnThreshold();
		if (!unlimitedPending && warnThreshold > 0 && retryState.attempts >= warnThreshold && !retryState.warnLogged) {
			retryState.warnLogged = true;
			RedstoneLink.LOGGER.warn(
				"[CrossChunkRetry] Retry warning threshold reached, kind={}, source={}#{}, target={}#{}, version={}, attempts={}",
				pending.key().dispatchKind(),
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.key().targetType(),
				pending.key().targetSerial(),
				pending.version(),
				retryState.attempts
			);
		}

		int errorThreshold = RedstoneLinkConfig.crossChunk().retry().errorThreshold();
		if (!unlimitedPending && errorThreshold > 0 && retryState.attempts >= errorThreshold && !retryState.errorLogged) {
			retryState.errorLogged = true;
			RedstoneLink.LOGGER.error(
				"[CrossChunkRetry] Retry error threshold reached, kind={}, source={}#{}, target={}#{}, version={}, attempts={}",
				pending.key().dispatchKind(),
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.key().targetType(),
				pending.key().targetSerial(),
				pending.version(),
				retryState.attempts
			);
		}

		int dropThreshold = RedstoneLinkConfig.crossChunk().retry().dropThreshold();
		if (unlimitedPending) {
			int retryStage = RedstoneLinkConfig.crossChunk().retry().persistentStageIndex(retryState.attempts);
			if (retryStage > 1 && !retryState.stagedBackoffLogged) {
				retryState.stagedBackoffLogged = true;
				RedstoneLink.LOGGER.warn(
					"[CrossChunkRetry] Unlimited pending entered staged backoff retry, kind={}, source={}#{}, target={}#{}, version={}, attempts={}, stage={}, intervalTicks={}",
					pending.key().dispatchKind(),
					pending.key().sourceType(),
					pending.key().sourceSerial(),
					pending.key().targetType(),
					pending.key().targetSerial(),
					pending.version(),
					retryState.attempts,
					retryStage,
					RedstoneLinkConfig.crossChunk().retry().persistentIntervalTicks(retryState.attempts)
				);
			}
			return false;
		}

		if (dropThreshold <= 0 || retryState.attempts < dropThreshold) {
			return false;
		}

		if (!retryState.dropLogged) {
			retryState.dropLogged = true;
			RedstoneLink.LOGGER.error(
				"[CrossChunkRetry] Non-persistent event dropped after reaching the retry limit, kind={}, source={}#{}, target={}#{}, version={}, attempts={}, dropThreshold={}",
				pending.key().dispatchKind(),
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.key().targetType(),
				pending.key().targetSerial(),
				pending.version(),
				retryState.attempts,
				dropThreshold
			);
		}
		return true;
	}

	/**
	 * 目标区块加载时，唤醒该区块上的不限时 pending。
	 */
	static int notifyTargetChunkLoaded(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		ResourceKey<Level> dimension,
		ChunkPos chunkPos,
		long gameTime
	) {
		if (state == null || queueData == null || dimension == null || chunkPos == null) {
			return 0;
		}
		CrossChunkDispatchService.TargetChunkKey targetChunkKey =
			new CrossChunkDispatchService.TargetChunkKey(dimension, chunkPos.x, chunkPos.z);
		Set<CrossChunkDispatchService.PendingAttemptKey> indexedAttemptKeys =
			state.waitingUnlimitedAttemptKeysByTargetChunk.get(targetChunkKey);
		if (indexedAttemptKeys == null || indexedAttemptKeys.isEmpty()) {
			return 0;
		}
		int awakened = 0;
		List<CrossChunkDispatchService.PendingAttemptKey> snapshot = state.wakeAttemptSnapshotScratch;
		snapshot.clear();
		snapshot.addAll(indexedAttemptKeys);
		for (CrossChunkDispatchService.PendingAttemptKey attemptKey : snapshot) {
			CrossChunkDispatchService.RetryState retryState = state.retryStateByAttemptKey.get(attemptKey);
			if (retryState == null) {
				removePendingAttemptFromWakeIndex(state, attemptKey, targetChunkKey);
				continue;
			}
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = queueData.pendingEntry(attemptKey.key()).orElse(null);
			if (
				pending == null
					|| pending.version() != attemptKey.version()
					|| !isUnlimitedPending(pending)
					|| !targetChunkKey.equals(targetChunkKeyOf(pending))
			) {
				removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
				continue;
			}
			if (retryState.nextEligibleTick <= gameTime) {
				removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
				continue;
			}
			retryState.nextEligibleTick = gameTime;
			removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
			awakened++;
		}
		snapshot.clear();
		return awakened;
	}

	/**
	 * 解析不限时 pending 的重试间隔。
	 */
	static long resolvePersistentRetryIntervalTicks(int attempts) {
		return Math.max(1L, RedstoneLinkConfig.crossChunk().retry().persistentIntervalTicks(attempts));
	}

	/**
	 * 计算下一次允许重试的 tick。
	 */
	static long computeNextEligibleTick(long gameTime, long intervalTicks) {
		return gameTime + Math.max(1L, intervalTicks);
	}

	/**
	 * 建立“目标区块 -> pending attempt”唤醒索引。
	 */
	static void indexWaitingUnlimitedPending(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchService.PendingAttemptKey attemptKey,
		CrossChunkDispatchService.RetryState retryState,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		if (state == null || attemptKey == null || retryState == null || pending == null || !isUnlimitedPending(pending)) {
			return;
		}
		removePendingAttemptFromWakeIndex(state, attemptKey, retryState);
		CrossChunkDispatchService.TargetChunkKey targetChunkKey = targetChunkKeyOf(pending);
		if (targetChunkKey == null) {
			return;
		}
		retryState.targetChunkKey = targetChunkKey;
		state.waitingUnlimitedAttemptKeysByTargetChunk.computeIfAbsent(targetChunkKey, ignored -> new HashSet<>()).add(attemptKey);
	}

	/**
	 * 从唤醒索引中移除 attempt key。
	 */
	static void removePendingAttemptFromWakeIndex(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchService.PendingAttemptKey attemptKey,
		CrossChunkDispatchService.RetryState retryState
	) {
		if (retryState == null) {
			return;
		}
		removePendingAttemptFromWakeIndex(state, attemptKey, retryState.targetChunkKey);
		retryState.targetChunkKey = null;
	}

	/**
	 * 从指定目标区块桶中移除 attempt key。
	 */
	static void removePendingAttemptFromWakeIndex(
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchService.PendingAttemptKey attemptKey,
		CrossChunkDispatchService.TargetChunkKey targetChunkKey
	) {
		if (state == null || attemptKey == null || targetChunkKey == null) {
			return;
		}
		Set<CrossChunkDispatchService.PendingAttemptKey> indexedAttemptKeys =
			state.waitingUnlimitedAttemptKeysByTargetChunk.get(targetChunkKey);
		if (indexedAttemptKeys == null) {
			return;
		}
		indexedAttemptKeys.remove(attemptKey);
		if (indexedAttemptKeys.isEmpty()) {
			state.waitingUnlimitedAttemptKeysByTargetChunk.remove(targetChunkKey);
		}
	}

	/**
	 * 清空全部重试状态与唤醒索引。
	 */
	static void clearRetryTracking(CrossChunkDispatchService.DispatchState state) {
		if (state == null) {
			return;
		}
		state.retryStateByAttemptKey.clear();
		state.waitingUnlimitedAttemptKeysByTargetChunk.clear();
	}

	/**
	 * 解析 pending 目标所在区块键。
	 */
	static CrossChunkDispatchService.TargetChunkKey targetChunkKeyOf(
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		if (pending == null || pending.dimension() == null || pending.pos() == null) {
			return null;
		}
		return new CrossChunkDispatchService.TargetChunkKey(
			pending.dimension(),
			pending.pos().getX() >> 4,
			pending.pos().getZ() >> 4
		);
	}

	/**
	 * 获取目标区块加载通知采用的当前时间键。
	 */
	static long resolveGameTimeForTargetChunkLoad(MinecraftServer server, ResourceKey<Level> dimension) {
		if (server == null) {
			return 0L;
		}
		ServerLevel targetLevel = dimension == null ? null : server.getLevel(dimension);
		if (targetLevel != null) {
			return targetLevel.getGameTime();
		}
		ServerLevel overworld = server.overworld();
		return overworld == null ? 0L : overworld.getGameTime();
	}

	/**
	 * 判断条目是否为不限时 pending。
	 */
	static boolean isUnlimitedPending(CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending) {
		if (pending == null || pending.key() == null) {
			return false;
		}
		return pending.expireGameTick() == Long.MAX_VALUE;
	}
}
