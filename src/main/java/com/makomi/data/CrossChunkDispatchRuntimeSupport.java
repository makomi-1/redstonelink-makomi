package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.config.RedstoneLinkConfig;
import java.util.HashSet;
import java.util.Iterator;
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
			return;
		}
		queueData.purgeExpired(gameTime);
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> snapshot = queueData.pendingEntriesSnapshot();
		if (snapshot.isEmpty()) {
			clearRetryTracking(state);
			state.pendingCursor = 0L;
			return;
		}
		pruneRetryStateBySnapshot(state, snapshot);

		int budget = Math.max(1, RedstoneLinkConfig.crossChunk().dispatchMaxPerTick());
		int snapshotSize = snapshot.size();
		int startIndex = Math.floorMod(state.pendingCursor, snapshotSize);
		int processed = 0;
		int visited = 0;
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
			if (tryDispatch(server, state, queueData, pending, gameTime)) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
				continue;
			}
			if (recordRetryFailureAndShouldDrop(state, pending, gameTime)) {
				queueData.removePending(pending.key());
				clearRetryState(state, pending);
			}
		}
		if (queueData.pendingSize() <= 0) {
			clearRetryTracking(state);
			state.pendingCursor = 0L;
			return;
		}
		state.pendingCursor = state.pendingCursor + processed;
	}

	/**
	 * 尝试将单条 pending 投递到已加载目标。
	 */
	static boolean tryDispatch(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime
	) {
		if (queueData.isStaleByAcceptedVersion(pending.key(), pending.version())) {
			return true;
		}
		ServerLevel targetLevel = server.getLevel(pending.dimension());
		if (targetLevel == null) {
			return false;
		}
		if (!targetLevel.isLoaded(pending.pos())) {
			if (CrossChunkDispatchService.shouldForceLoad(targetLevel, pending)) {
				CrossChunkDispatchService.tryForceLoad(server, state, pending, gameTime);
			}
			return false;
		}

		LevelChunk targetChunk = targetLevel.getChunkSource().getChunkNow(pending.pos().getX() >> 4, pending.pos().getZ() >> 4);
		if (targetChunk == null) {
			return false;
		}
		BlockEntity blockEntity = targetChunk.getBlockEntity(pending.pos(), LevelChunk.EntityCreationType.CHECK);
		if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
			if (blockEntity == null && targetChunk.getBlockState(pending.pos()).hasBlockEntity()) {
				return false;
			}
			LinkSavedData.get(targetLevel).removeNode(pending.key().targetType(), pending.key().targetSerial());
			return true;
		}

		ActivatableTargetBlockEntity.DeltaKind deltaKind = switch (pending.key().dispatchKind()) {
			case ACTIVATION, PULSE_EVENT, TOGGLE_EVENT -> ActivatableTargetBlockEntity.DeltaKind.ACTIVATION;
			case SYNC_SIGNAL -> ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL;
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION ->
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION;
			case TRIGGER_SOURCE_INVALIDATION -> ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION;
		};
		ActivatableTargetBlockEntity.DeltaAction deltaAction = pending.dispatchAction() == CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE
			? ActivatableTargetBlockEntity.DeltaAction.REMOVE
			: ActivatableTargetBlockEntity.DeltaAction.UPSERT;
		EventMeta eventMeta = EventMeta.of(pending.enqueueGameTick(), pending.enqueueGameSlot(), pending.version());
		if (CoreDispatchBatchScheduler.supportsBatching(deltaKind)) {
			CoreDispatchBatchScheduler.enqueueLoadedTargetDispatch(
				server,
				targetBlockEntity,
				pending.key().targetType(),
				pending.key().targetSerial(),
				deltaKind,
				deltaAction,
				pending.key().sourceType(),
				pending.key().sourceSerial(),
				pending.activationMode(),
				pending.syncSignalStrength(),
				eventMeta
			);
		} else {
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
		queueData.markAccepted(pending.key(), pending.version());
		return true;
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
		Set<CrossChunkDispatchService.PendingAttemptKey> activeKeys = new HashSet<>(snapshot.size());
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
		List<CrossChunkDispatchService.PendingAttemptKey> snapshot = List.copyOf(indexedAttemptKeys);
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
