package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 跨区块持久队列状态 helper。
 * <p>
 * 负责 pending 条目的内存态维护，包括版本分配、批量 upsert、过期堆与快照缓存。
 * </p>
 */
final class CrossChunkDispatchQueueStateSupport {
	private static final Comparator<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> PENDING_ENTRY_COMPARATOR = Comparator
		.comparingLong(CrossChunkDispatchQueueSavedData.PendingDispatchEntry::expireGameTick)
		.thenComparingLong(CrossChunkDispatchQueueSavedData.PendingDispatchEntry::enqueueGameTick)
		.thenComparingInt(CrossChunkDispatchQueueSavedData.PendingDispatchEntry::enqueueGameSlot)
		.thenComparing((CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry) -> entry.key(), CrossChunkDispatchQueueSavedData.DISPATCH_KEY_COMPARATOR)
		.thenComparing(entry -> entry.dispatchAction().name())
		.thenComparingLong(CrossChunkDispatchQueueSavedData.PendingDispatchEntry::version);

	private static final Comparator<ExpireIndex> EXPIRE_INDEX_COMPARATOR = Comparator
		.comparingLong(ExpireIndex::expireGameTick)
		.thenComparing(ExpireIndex::key, CrossChunkDispatchQueueSavedData.DISPATCH_KEY_COMPARATOR)
		.thenComparingLong(ExpireIndex::version);

	private CrossChunkDispatchQueueStateSupport() {
	}

	/**
	 * 构造单条 pending 条目并分配版本号。
	 */
	static Optional<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> buildPendingEntry(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.DispatchKey key,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long expireGameTick
	) {
		if (!isValidPendingEntryInput(key, dispatchAction, dimension, pos, activationMode, expireGameTick)) {
			return Optional.empty();
		}
		long version = allocateNextVersion(data, key);
		return Optional.of(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key,
				dispatchAction,
				dimension,
				pos.immutable(),
				activationMode,
				SignalStrengths.clamp(syncSignalStrength),
				Math.max(0L, enqueueGameTick),
				Math.max(0, enqueueGameSlot),
				expireGameTick,
				version
			)
		);
	}

	/**
	 * 批量写入 pending 条目，并回收“本批最后有效请求”的结果。
	 */
	static BatchUpsertOutcome upsertPendingBatch(
		CrossChunkDispatchQueueSavedData data,
		List<CrossChunkDispatchQueueSavedData.PendingUpsertRequest> requests
	) {
		if (requests == null || requests.isEmpty()) {
			return new BatchUpsertOutcome(List.of(), false);
		}
		List<CrossChunkDispatchQueueSavedData.UpsertResult> results = new ArrayList<>(requests.size());
		for (int index = 0; index < requests.size(); index++) {
			results.add(new CrossChunkDispatchQueueSavedData.UpsertResult(false, null));
		}

		Map<CrossChunkDispatchQueueSavedData.DispatchKey, Integer> lastValidIndexByKey = new HashMap<>();
		for (int index = 0; index < requests.size(); index++) {
			CrossChunkDispatchQueueSavedData.PendingUpsertRequest request = requests.get(index);
			if (!isValidPendingEntryInput(
				request == null ? null : request.key(),
				request == null ? null : request.dispatchAction(),
				request == null ? null : request.dimension(),
				request == null ? null : request.pos(),
				request == null ? null : request.activationMode(),
				request == null ? 0L : request.expireGameTick()
			)) {
				continue;
			}
			lastValidIndexByKey.put(request.key(), index);
		}

		boolean dirty = false;
		Map<CrossChunkDispatchQueueSavedData.DispatchKey, CrossChunkDispatchQueueSavedData.UpsertResult> resultByKey = new HashMap<>();
		for (int index = 0; index < requests.size(); index++) {
			CrossChunkDispatchQueueSavedData.PendingUpsertRequest request = requests.get(index);
			if (request == null) {
				continue;
			}
			Integer lastIndex = lastValidIndexByKey.get(request.key());
			if (lastIndex == null || lastIndex.intValue() != index) {
				continue;
			}
			Optional<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> normalized = buildPendingEntry(
				data,
				request.key(),
				request.dispatchAction(),
				request.dimension(),
				request.pos(),
				request.activationMode(),
				request.syncSignalStrength(),
				request.enqueueGameTick(),
				request.enqueueGameSlot(),
				request.expireGameTick()
			);
			if (normalized.isEmpty()) {
				continue;
			}
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingEntry = normalized.get();
			if (upsertPendingEntry(data, pendingEntry)) {
				dirty = true;
			}
			resultByKey.put(request.key(), new CrossChunkDispatchQueueSavedData.UpsertResult(true, pendingEntry));
		}

		for (int index = 0; index < requests.size(); index++) {
			CrossChunkDispatchQueueSavedData.PendingUpsertRequest request = requests.get(index);
			if (request == null) {
				continue;
			}
			if (!isValidPendingEntryInput(
				request.key(),
				request.dispatchAction(),
				request.dimension(),
				request.pos(),
				request.activationMode(),
				request.expireGameTick()
			)) {
				continue;
			}
			CrossChunkDispatchQueueSavedData.UpsertResult mappedResult = resultByKey.get(request.key());
			if (mappedResult != null) {
				results.set(index, mappedResult);
			}
		}
		return new BatchUpsertOutcome(List.copyOf(results), dirty);
	}

	/**
	 * 删除单条 pending。
	 */
	static boolean removePending(CrossChunkDispatchQueueSavedData data, CrossChunkDispatchQueueSavedData.DispatchKey key) {
		if (key == null) {
			return false;
		}
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry removed = data.pendingByKey.remove(key);
		if (removed == null) {
			return false;
		}
		markPendingSnapshotDirty(data);
		return true;
	}

	/**
	 * 判断给定版本是否已落后于 accepted 护栏。
	 */
	static boolean isStaleByAcceptedVersion(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.DispatchKey key,
		long version
	) {
		if (key == null || version <= 0L) {
			return true;
		}
		return version <= data.lastAcceptedVersionByKey.getOrDefault(key, 0L);
	}

	/**
	 * 推进 accepted 护栏，同时对 issued 护栏做追平。
	 */
	static boolean markAccepted(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.DispatchKey key,
		long version
	) {
		if (key == null || version <= 0L) {
			return false;
		}
		long previous = data.lastAcceptedVersionByKey.getOrDefault(key, 0L);
		if (version <= previous) {
			return false;
		}
		data.lastAcceptedVersionByKey.put(key, version);
		long issued = data.maxIssuedVersionByKey.getOrDefault(key, 0L);
		if (version > issued) {
			data.maxIssuedVersionByKey.put(key, version);
		}
		return true;
	}

	/**
	 * 读取当前 key 的 pending 条目。
	 */
	static Optional<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> pendingEntry(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.DispatchKey key
	) {
		if (key == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(data.pendingByKey.get(key));
	}

	/**
	 * 清理当前 tick 前已过期的 pending。
	 */
	static int purgeExpired(CrossChunkDispatchQueueSavedData data, long nowGameTick) {
		if (nowGameTick < 0L || data.pendingByKey.isEmpty()) {
			return 0;
		}
		int removed = 0;
		ensureExpireHeapReady(data);
		while (true) {
			ExpireIndex expireIndex = data.expireMinHeap.peek();
			if (expireIndex == null || expireIndex.expireGameTick() > nowGameTick) {
				break;
			}
			data.expireMinHeap.poll();
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry current = data.pendingByKey.get(expireIndex.key());
			if (current == null) {
				continue;
			}
			if (current.version() != expireIndex.version() || current.expireGameTick() != expireIndex.expireGameTick()) {
				continue;
			}
			data.pendingByKey.remove(expireIndex.key());
			removed++;
		}
		if (removed > 0) {
			markPendingSnapshotDirty(data);
		}
		return removed;
	}

	/**
	 * 返回 pending 快照；仅在脏时重建。
	 */
	static List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> pendingEntriesSnapshot(CrossChunkDispatchQueueSavedData data) {
		if (data.pendingByKey.isEmpty()) {
			return List.of();
		}
		if (!data.pendingSnapshotDirty) {
			return data.pendingSnapshotCache;
		}
		data.pendingSnapshotCache = List.copyOf(data.pendingByKey.values());
		data.pendingSnapshotDirty = false;
		return data.pendingSnapshotCache;
	}

	/**
	 * 返回用于持久化的稳定排序快照。
	 */
	static List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> pendingEntriesForSave(CrossChunkDispatchQueueSavedData data) {
		if (data.pendingByKey.isEmpty()) {
			return List.of();
		}
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> entries = new ArrayList<>(data.pendingByKey.values());
		entries.sort(PENDING_ENTRY_COMPARATOR);
		return entries;
	}

	/**
	 * 恢复读档后的 pending 条目，并同步 issued 护栏。
	 */
	static void restorePendingEntry(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry
	) {
		data.pendingByKey.put(entry.key(), entry);
		trackExpire(data, entry);
		markPendingSnapshotDirty(data);
		long baseline = Math.max(
			data.maxIssuedVersionByKey.getOrDefault(entry.key(), 0L),
			data.lastAcceptedVersionByKey.getOrDefault(entry.key(), 0L)
		);
		if (entry.version() > baseline) {
			data.maxIssuedVersionByKey.put(entry.key(), entry.version());
		}
	}

	/**
	 * 写入或覆盖内存态 pending。
	 */
	static boolean upsertPendingEntry(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry
	) {
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry previous = data.pendingByKey.put(entry.key(), entry);
		trackExpire(data, entry);
		markPendingSnapshotDirty(data);
		return !entry.equals(previous);
	}

	/**
	 * 校验 pending 请求的结构字段是否有效。
	 */
	static boolean isValidPendingEntryInput(
		CrossChunkDispatchQueueSavedData.DispatchKey key,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		long expireGameTick
	) {
		if (key == null || dispatchAction == null || dimension == null || pos == null || activationMode == null) {
			return false;
		}
		if (!LinkNodeSemantics.isAllowedForRole(key.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return false;
		}
		if (!LinkNodeSemantics.isAllowedForRole(key.targetType(), LinkNodeSemantics.Role.TARGET)) {
			return false;
		}
		if (key.sourceSerial() <= 0L || key.targetSerial() <= 0L || expireGameTick <= 0L) {
			return false;
		}
		return true;
	}

	/**
	 * 分配单 key 单调递增版本号。
	 */
	static long allocateNextVersion(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.DispatchKey key
	) {
		long baseline = Math.max(
			data.lastAcceptedVersionByKey.getOrDefault(key, 0L),
			data.maxIssuedVersionByKey.getOrDefault(key, 0L)
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingEntry = data.pendingByKey.get(key);
		if (pendingEntry != null && pendingEntry.version() > baseline) {
			baseline = pendingEntry.version();
		}
		long nextVersion = baseline + 1L;
		data.maxIssuedVersionByKey.put(key, nextVersion);
		return nextVersion;
	}

	/**
	 * 记录过期闹钟索引。
	 */
	private static void trackExpire(
		CrossChunkDispatchQueueSavedData data,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry
	) {
		if (data.expireMinHeap == null) {
			data.expireMinHeap = new PriorityQueue<>(EXPIRE_INDEX_COMPARATOR);
		}
		data.expireMinHeap.offer(new ExpireIndex(entry.key(), entry.expireGameTick(), entry.version()));
	}

	/**
	 * 确保过期最小堆可用。
	 */
	private static void ensureExpireHeapReady(CrossChunkDispatchQueueSavedData data) {
		if (data.expireMinHeap != null) {
			return;
		}
		data.expireMinHeap = new PriorityQueue<>(EXPIRE_INDEX_COMPARATOR);
		for (CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry : data.pendingByKey.values()) {
			data.expireMinHeap.offer(new ExpireIndex(entry.key(), entry.expireGameTick(), entry.version()));
		}
	}

	/**
	 * 标记 pending 快照缓存失效。
	 */
	private static void markPendingSnapshotDirty(CrossChunkDispatchQueueSavedData data) {
		data.pendingSnapshotDirty = true;
		data.pendingSnapshotCache = List.of();
	}

	/**
	 * 批量 upsert 的结果封装。
	 */
	record BatchUpsertOutcome(List<CrossChunkDispatchQueueSavedData.UpsertResult> results, boolean dirty) {}

	/**
	 * 过期最小堆索引项。
	 */
	record ExpireIndex(
		CrossChunkDispatchQueueSavedData.DispatchKey key,
		long expireGameTick,
		long version
	) {}
}
