package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.mojang.serialization.Codec;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 跨区块派发持久队列。
 * <p>
 * 作为跨区块 relay 主路径的结构真值，持久化 pending 与版本护栏状态。
 * </p>
 */
public final class CrossChunkDispatchQueueSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_crosschunk_dispatch_queue";
	static final String KEY_PENDING_ENTRIES = "pendingEntries";
	static final String KEY_ACCEPTED_VERSIONS = "acceptedVersions";
	static final String KEY_ISSUED_VERSIONS = "issuedVersions";
	static final String KEY_SOURCE_TYPE = "sourceType";
	static final String KEY_SOURCE_SERIAL = "sourceSerial";
	static final String KEY_TARGET_TYPE = "targetType";
	static final String KEY_TARGET_SERIAL = "targetSerial";
	static final String KEY_DISPATCH_KIND = "dispatchKind";
	static final String KEY_DISPATCH_ACTION = "dispatchAction";
	static final String KEY_DIMENSION = "dimension";
	static final String KEY_POS = "pos";
	static final String KEY_ACTIVATION_MODE = "activationMode";
	static final String KEY_SYNC_SIGNAL_STRENGTH = "syncSignalStrength";
	static final String KEY_ENQUEUE_TICK = "enqueueTick";
	static final String KEY_ENQUEUE_SLOT = "enqueueSlot";
	static final String KEY_EXPIRE_TICK = "expireTick";
	static final String KEY_VERSION = "version";

	private static final Codec<CrossChunkDispatchQueueSavedData> CODEC = CompoundTag.CODEC.xmap(
		CrossChunkDispatchQueueSavedData::load,
		CrossChunkDispatchQueueSavedData::toTag
	);
	private static final SavedDataType<CrossChunkDispatchQueueSavedData> TYPE = SavedDataTypeSupport.levelType(
		DATA_NAME,
		CrossChunkDispatchQueueSavedData::new,
		CODEC
	);

	static final Comparator<DispatchKey> DISPATCH_KEY_COMPARATOR = Comparator
		.comparing((DispatchKey key) -> key.sourceType().name())
		.thenComparingLong(DispatchKey::sourceSerial)
		.thenComparing(key -> key.targetType().name())
		.thenComparingLong(DispatchKey::targetSerial)
		.thenComparing(key -> key.dispatchKind().name());

	final Map<DispatchKey, PendingDispatchEntry> pendingByKey = new LinkedHashMap<>();
	final Map<DispatchKey, Long> lastAcceptedVersionByKey = new HashMap<>();
	final Map<DispatchKey, Long> maxIssuedVersionByKey = new HashMap<>();
	transient List<PendingDispatchEntry> pendingSnapshotCache = List.of();
	transient boolean pendingSnapshotDirty = true;
	transient PriorityQueue<CrossChunkDispatchQueueStateSupport.ExpireIndex> expireMinHeap = null;

	/**
	 * 获取跨区块持久队列实例。
	 */
	public static CrossChunkDispatchQueueSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	private static CrossChunkDispatchQueueSavedData load(CompoundTag tag) {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		ListTag acceptedVersions = tag.getListOrEmpty(KEY_ACCEPTED_VERSIONS);
		ListTag issuedVersions = tag.getListOrEmpty(KEY_ISSUED_VERSIONS);
		data.readVersionMap(acceptedVersions, data.lastAcceptedVersionByKey);
		data.readVersionMap(issuedVersions, data.maxIssuedVersionByKey);
		ListTag pendingEntries = tag.getListOrEmpty(KEY_PENDING_ENTRIES);
		for (Tag element : pendingEntries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<PendingDispatchEntry> parsed = parsePendingEntry(entryTag);
			if (parsed.isEmpty()) {
				continue;
			}
			data.restorePendingEntry(parsed.get());
		}
		return data;
	}

	/**
	 * 写入或覆盖 pending 条目（同 key upsert），并分配单调版本号。
	 */
	public UpsertResult upsertPending(
		DispatchKey key,
		DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long expireGameTick
	) {
		if (!CrossChunkDispatchQueueStateSupport.canAcceptPendingUpsert(this, key)) {
			return UpsertResult.rejected();
		}
		Optional<PendingDispatchEntry> normalized = buildPendingEntry(
			key,
			dispatchAction,
			dimension,
			pos,
			activationMode,
			syncSignalStrength,
			enqueueGameTick,
			enqueueGameSlot,
			expireGameTick
		);
		if (normalized.isEmpty()) {
			return UpsertResult.rejected();
		}
		PendingDispatchEntry pendingEntry = normalized.get();
		if (upsertPendingEntry(pendingEntry)) {
			setDirty();
		}
		return new UpsertResult(true, pendingEntry);
	}

	/**
	 * 批量写入或覆盖 pending 条目，仅在存在有效变更时统一 setDirty 一次。
	 */
	public List<UpsertResult> upsertPendingBatch(List<PendingUpsertRequest> requests) {
		CrossChunkDispatchQueueStateSupport.BatchUpsertOutcome outcome = CrossChunkDispatchQueueStateSupport.upsertPendingBatch(this, requests);
		if (outcome.dirty()) {
			setDirty();
		}
		return outcome.results();
	}

	/**
	 * 删除 pending 条目。
	 */
	public boolean removePending(DispatchKey key) {
		boolean removed = CrossChunkDispatchQueueStateSupport.removePending(this, key);
		if (removed) {
			setDirty();
		}
		return removed;
	}

	/**
	 * 按 key 判断版本是否为旧包。
	 */
	public boolean isStaleByAcceptedVersion(DispatchKey key, long version) {
		return CrossChunkDispatchQueueStateSupport.isStaleByAcceptedVersion(this, key, version);
	}

	/**
	 * 标记 key 已接受版本。
	 */
	public boolean markAccepted(DispatchKey key, long version) {
		boolean advanced = CrossChunkDispatchQueueStateSupport.markAccepted(this, key, version);
		if (advanced) {
			setDirty();
		}
		return advanced;
	}

	/**
	 * 按 key 查询当前 pending 条目。
	 */
	public Optional<PendingDispatchEntry> pendingEntry(DispatchKey key) {
		return CrossChunkDispatchQueueStateSupport.pendingEntry(this, key);
	}

	/**
	 * 清理过期 pending 条目（基于最小堆闹钟，避免每 tick 全量扫描过期）。
	 */
	public int purgeExpired(long nowGameTick) {
		int removed = CrossChunkDispatchQueueStateSupport.purgeExpired(this, nowGameTick);
		if (removed > 0) {
			setDirty();
		}
		return removed;
	}

	/**
	 * 返回 pending 快照（脏标记缓存，避免重复创建与排序）。
	 */
	public List<PendingDispatchEntry> pendingEntriesSnapshot() {
		return CrossChunkDispatchQueueStateSupport.pendingEntriesSnapshot(this);
	}

	/**
	 * 返回 pending 条目数量。
	 */
	public int pendingSize() {
		return pendingByKey.size();
	}

	private CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		ListTag pendingEntries = new ListTag();
		for (PendingDispatchEntry entry : pendingEntriesForSave()) {
			CompoundTag entryTag = new CompoundTag();
			writeDispatchKey(entryTag, entry.key());
			entryTag.putString(KEY_DIMENSION, entry.dimension().identifier().toString());
			entryTag.putLong(KEY_POS, entry.pos().asLong());
			entryTag.putString(KEY_ACTIVATION_MODE, entry.activationMode().name());
			entryTag.putString(KEY_DISPATCH_ACTION, entry.dispatchAction().name());
			entryTag.putInt(KEY_SYNC_SIGNAL_STRENGTH, com.makomi.util.SignalStrengths.clamp(entry.syncSignalStrength()));
			entryTag.putLong(KEY_ENQUEUE_TICK, Math.max(0L, entry.enqueueGameTick()));
			entryTag.putInt(KEY_ENQUEUE_SLOT, Math.max(0, entry.enqueueGameSlot()));
			entryTag.putLong(KEY_EXPIRE_TICK, Math.max(0L, entry.expireGameTick()));
			entryTag.putLong(KEY_VERSION, Math.max(0L, entry.version()));
			pendingEntries.add(entryTag);
		}
		tag.put(KEY_PENDING_ENTRIES, pendingEntries);
		tag.put(KEY_ACCEPTED_VERSIONS, writeVersionMap(lastAcceptedVersionByKey));
		tag.put(KEY_ISSUED_VERSIONS, writeVersionMap(maxIssuedVersionByKey));
		return tag;
	}

	private Optional<PendingDispatchEntry> buildPendingEntry(
		DispatchKey key,
		DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long expireGameTick
	) {
		return CrossChunkDispatchQueueStateSupport.buildPendingEntry(
			this,
			key,
			dispatchAction,
			dimension,
			pos,
			activationMode,
			syncSignalStrength,
			enqueueGameTick,
			enqueueGameSlot,
			expireGameTick
		);
	}

	private boolean upsertPendingEntry(PendingDispatchEntry entry) {
		return CrossChunkDispatchQueueStateSupport.upsertPendingEntry(this, entry);
	}

	private void restorePendingEntry(PendingDispatchEntry entry) {
		CrossChunkDispatchQueueStateSupport.restorePendingEntry(this, entry);
	}

	private List<PendingDispatchEntry> pendingEntriesForSave() {
		return CrossChunkDispatchQueueStateSupport.pendingEntriesForSave(this);
	}

	private void readVersionMap(ListTag listTag, Map<DispatchKey, Long> target) {
		CrossChunkDispatchQueueCodecSupport.readVersionMap(listTag, target);
	}

	private static ListTag writeVersionMap(Map<DispatchKey, Long> versionByKey) {
		return CrossChunkDispatchQueueCodecSupport.writeVersionMap(versionByKey);
	}

	private static void writeDispatchKey(CompoundTag tag, DispatchKey key) {
		CrossChunkDispatchQueueCodecSupport.writeDispatchKey(tag, key);
	}

	private static Optional<PendingDispatchEntry> parsePendingEntry(CompoundTag tag) {
		return CrossChunkDispatchQueueCodecSupport.parsePendingEntry(tag);
	}

	/**
	 * 入队结果快照。
	 */
	public record UpsertResult(boolean accepted, PendingDispatchEntry entry) {
		private static UpsertResult rejected() {
			return new UpsertResult(false, null);
		}
	}

	/**
	 * 派发语义类型。
	 */
	public enum DispatchKind {
		PULSE_EVENT,
		TOGGLE_EVENT,
		SYNC_SIGNAL,
		SOURCE_INVALIDATION,
		TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
		TRIGGER_SOURCE_INVALIDATION;

		static Optional<DispatchKind> fromName(String raw) {
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			for (DispatchKind value : values()) {
				if (value.name().equalsIgnoreCase(raw.trim())) {
					return Optional.of(value);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * 派发动作：UPSERT 写入/更新来源贡献，REMOVE 剔除来源贡献并触发回退重算。
	 */
	public enum DispatchAction {
		UPSERT,
		REMOVE;

		static Optional<DispatchAction> fromName(String raw) {
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			for (DispatchAction value : values()) {
				if (value.name().equalsIgnoreCase(raw.trim())) {
					return Optional.of(value);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * 跨区块事件 key。
	 */
	public record DispatchKey(
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		DispatchKind dispatchKind
	) {}

	/**
	 * 持久队列条目。
	 */
	public record PendingDispatchEntry(
		DispatchKey key,
		DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long expireGameTick,
		long version
	) {}

	/**
	 * 批量入队请求项。
	 */
	public record PendingUpsertRequest(
		DispatchKey key,
		DispatchAction dispatchAction,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long expireGameTick
	) {}
}
