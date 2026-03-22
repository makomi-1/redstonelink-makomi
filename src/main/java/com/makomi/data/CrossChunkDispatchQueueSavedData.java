package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 跨区块派发持久队列。
 * <p>
 * 作为跨区块 relay 主路径的结构真值，持久化 pending 与版本护栏状态。
 * </p>
 */
public final class CrossChunkDispatchQueueSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_crosschunk_dispatch_queue";
	private static final String KEY_PENDING_ENTRIES = "pendingEntries";
	private static final String KEY_ACCEPTED_VERSIONS = "acceptedVersions";
	private static final String KEY_ISSUED_VERSIONS = "issuedVersions";
	private static final String KEY_SOURCE_TYPE = "sourceType";
	private static final String KEY_SOURCE_SERIAL = "sourceSerial";
	private static final String KEY_TARGET_TYPE = "targetType";
	private static final String KEY_TARGET_SERIAL = "targetSerial";
	private static final String KEY_DISPATCH_KIND = "dispatchKind";
	private static final String KEY_DISPATCH_ACTION = "dispatchAction";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_POS = "pos";
	private static final String KEY_ACTIVATION_MODE = "activationMode";
	private static final String KEY_SYNC_SIGNAL_STRENGTH = "syncSignalStrength";
	private static final String KEY_ENQUEUE_TICK = "enqueueTick";
	private static final String KEY_ENQUEUE_SLOT = "enqueueSlot";
	private static final String KEY_EXPIRE_TICK = "expireTick";
	private static final String KEY_VERSION = "version";

	private static final SavedData.Factory<CrossChunkDispatchQueueSavedData> FACTORY = new SavedData.Factory<>(
		CrossChunkDispatchQueueSavedData::new,
		CrossChunkDispatchQueueSavedData::load,
		DataFixTypes.LEVEL
	);

	private static final Comparator<DispatchKey> DISPATCH_KEY_COMPARATOR = Comparator
		.comparing((DispatchKey key) -> key.sourceType().name())
		.thenComparingLong(DispatchKey::sourceSerial)
		.thenComparing(key -> key.targetType().name())
		.thenComparingLong(DispatchKey::targetSerial)
		.thenComparing(key -> key.dispatchKind().name());

	private static final Comparator<PendingDispatchEntry> PENDING_ENTRY_COMPARATOR = Comparator
		.comparingLong(PendingDispatchEntry::expireGameTick)
		.thenComparingLong(PendingDispatchEntry::enqueueGameTick)
		.thenComparingInt(PendingDispatchEntry::enqueueGameSlot)
		.thenComparing((PendingDispatchEntry entry) -> entry.key(), DISPATCH_KEY_COMPARATOR)
		.thenComparing(entry -> entry.dispatchAction().name())
		.thenComparingLong(PendingDispatchEntry::version);

	private static final Comparator<ExpireIndex> EXPIRE_INDEX_COMPARATOR = Comparator
		.comparingLong(ExpireIndex::expireGameTick)
		.thenComparing(ExpireIndex::key, DISPATCH_KEY_COMPARATOR)
		.thenComparingLong(ExpireIndex::version);

	private final Map<DispatchKey, PendingDispatchEntry> pendingByKey = new LinkedHashMap<>();
	private final Map<DispatchKey, Long> lastAcceptedVersionByKey = new HashMap<>();
	private final Map<DispatchKey, Long> maxIssuedVersionByKey = new HashMap<>();
	private transient List<PendingDispatchEntry> pendingSnapshotCache = List.of();
	private transient boolean pendingSnapshotDirty = true;
	private transient PriorityQueue<ExpireIndex> expireMinHeap = new PriorityQueue<>(EXPIRE_INDEX_COMPARATOR);

	/**
	 * 获取跨区块持久队列实例。
	 */
	public static CrossChunkDispatchQueueSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static CrossChunkDispatchQueueSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		ListTag acceptedVersions = tag.getList(KEY_ACCEPTED_VERSIONS, Tag.TAG_COMPOUND);
		ListTag issuedVersions = tag.getList(KEY_ISSUED_VERSIONS, Tag.TAG_COMPOUND);
		data.readVersionMap(acceptedVersions, data.lastAcceptedVersionByKey);
		data.readVersionMap(issuedVersions, data.maxIssuedVersionByKey);
		ListTag pendingEntries = tag.getList(KEY_PENDING_ENTRIES, Tag.TAG_COMPOUND);
		for (Tag element : pendingEntries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<PendingDispatchEntry> parsed = parsePendingEntry(entryTag);
			if (parsed.isEmpty()) {
				continue;
			}
			PendingDispatchEntry entry = parsed.get();
			data.restorePendingEntry(entry);
			long baseline = Math.max(
				data.maxIssuedVersionByKey.getOrDefault(entry.key(), 0L),
				data.lastAcceptedVersionByKey.getOrDefault(entry.key(), 0L)
			);
			if (entry.version() > baseline) {
				data.maxIssuedVersionByKey.put(entry.key(), entry.version());
			}
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
		if (requests == null || requests.isEmpty()) {
			return List.of();
		}
		List<UpsertResult> results = new ArrayList<>(requests.size());
		for (int index = 0; index < requests.size(); index++) {
			results.add(UpsertResult.rejected());
		}

		// 批次内同 key 去重：仅保留最后一条有效请求，避免重复分配版本与重复 upsert。
		Map<DispatchKey, Integer> lastValidIndexByKey = new HashMap<>();
		for (int index = 0; index < requests.size(); index++) {
			PendingUpsertRequest request = requests.get(index);
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
		Map<DispatchKey, UpsertResult> resultByKey = new HashMap<>();
		for (int index = 0; index < requests.size(); index++) {
			PendingUpsertRequest request = requests.get(index);
			if (request == null) {
				continue;
			}
			Integer lastIndex = lastValidIndexByKey.get(request.key());
			if (lastIndex == null || lastIndex.intValue() != index) {
				continue;
			}
			Optional<PendingDispatchEntry> normalized = buildPendingEntry(
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
			PendingDispatchEntry pendingEntry = normalized.get();
			if (upsertPendingEntry(pendingEntry)) {
				dirty = true;
			}
			resultByKey.put(request.key(), new UpsertResult(true, pendingEntry));
		}

		// 将“最后有效请求”的结果回填到本批内所有同 key 且有效的请求。
		for (int index = 0; index < requests.size(); index++) {
			PendingUpsertRequest request = requests.get(index);
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
			UpsertResult mappedResult = resultByKey.get(request.key());
			if (mappedResult != null) {
				results.set(index, mappedResult);
			}
		}
		if (dirty) {
			setDirty();
		}
		return List.copyOf(results);
	}

	/**
	 * 删除 pending 条目。
	 */
	public boolean removePending(DispatchKey key) {
		if (key == null) {
			return false;
		}
		PendingDispatchEntry removed = pendingByKey.remove(key);
		if (removed == null) {
			return false;
		}
		markPendingSnapshotDirty();
		setDirty();
		return true;
	}

	/**
	 * 按 key 判断版本是否为旧包。
	 */
	public boolean isStaleByAcceptedVersion(DispatchKey key, long version) {
		if (key == null || version <= 0L) {
			return true;
		}
		return version <= lastAcceptedVersionByKey.getOrDefault(key, 0L);
	}

	/**
	 * 标记 key 已接受版本。
	 */
	public boolean markAccepted(DispatchKey key, long version) {
		if (key == null || version <= 0L) {
			return false;
		}
		long previous = lastAcceptedVersionByKey.getOrDefault(key, 0L);
		if (version <= previous) {
			return false;
		}
		lastAcceptedVersionByKey.put(key, version);
		long issued = maxIssuedVersionByKey.getOrDefault(key, 0L);
		if (version > issued) {
			maxIssuedVersionByKey.put(key, version);
		}
		setDirty();
		return true;
	}

	/**
	 * 按 key 查询当前 pending 条目。
	 */
	public Optional<PendingDispatchEntry> pendingEntry(DispatchKey key) {
		if (key == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(pendingByKey.get(key));
	}

	/**
	 * 清理过期 pending 条目（基于最小堆闹钟，避免每 tick 全量扫描过期）。
	 */
	public int purgeExpired(long nowGameTick) {
		if (nowGameTick < 0L || pendingByKey.isEmpty()) {
			return 0;
		}
		int removed = 0;
		ensureExpireHeapReady();
		while (true) {
			ExpireIndex expireIndex = expireMinHeap.peek();
			if (expireIndex == null || expireIndex.expireGameTick() > nowGameTick) {
				break;
			}
			expireMinHeap.poll();
			PendingDispatchEntry current = pendingByKey.get(expireIndex.key());
			if (current == null) {
				continue;
			}
			if (current.version() != expireIndex.version() || current.expireGameTick() != expireIndex.expireGameTick()) {
				continue;
			}
			pendingByKey.remove(expireIndex.key());
			removed++;
		}
		if (removed > 0) {
			markPendingSnapshotDirty();
			setDirty();
		}
		return removed;
	}

	/**
	 * 返回 pending 快照（脏标记缓存，避免重复创建与排序）。
	 */
	public List<PendingDispatchEntry> pendingEntriesSnapshot() {
		if (pendingByKey.isEmpty()) {
			return List.of();
		}
		if (!pendingSnapshotDirty) {
			return pendingSnapshotCache;
		}
		pendingSnapshotCache = List.copyOf(pendingByKey.values());
		pendingSnapshotDirty = false;
		return pendingSnapshotCache;
	}

	/**
	 * 返回 pending 条目数量。
	 */
	public int pendingSize() {
		return pendingByKey.size();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag pendingEntries = new ListTag();
		for (PendingDispatchEntry entry : pendingEntriesForSave()) {
			CompoundTag entryTag = new CompoundTag();
			writeDispatchKey(entryTag, entry.key());
			entryTag.putString(KEY_DIMENSION, entry.dimension().location().toString());
			entryTag.putLong(KEY_POS, entry.pos().asLong());
			entryTag.putString(KEY_ACTIVATION_MODE, entry.activationMode().name());
			entryTag.putString(KEY_DISPATCH_ACTION, entry.dispatchAction().name());
			entryTag.putInt(KEY_SYNC_SIGNAL_STRENGTH, SignalStrengths.clamp(entry.syncSignalStrength()));
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
		if (!isValidPendingEntryInput(key, dispatchAction, dimension, pos, activationMode, expireGameTick)) {
			return Optional.empty();
		}
		long version = allocateNextVersion(key);
		return Optional.of(
			new PendingDispatchEntry(
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
	 * 校验 pending 请求基础字段合法性（不分配版本号）。
	 */
	private boolean isValidPendingEntryInput(
		DispatchKey key,
		DispatchAction dispatchAction,
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

	private boolean upsertPendingEntry(PendingDispatchEntry entry) {
		PendingDispatchEntry previous = pendingByKey.put(entry.key(), entry);
		trackExpire(entry);
		markPendingSnapshotDirty();
		return !entry.equals(previous);
	}

	private void restorePendingEntry(PendingDispatchEntry entry) {
		pendingByKey.put(entry.key(), entry);
		trackExpire(entry);
		markPendingSnapshotDirty();
	}

	private void trackExpire(PendingDispatchEntry entry) {
		if (expireMinHeap == null) {
			expireMinHeap = new PriorityQueue<>(EXPIRE_INDEX_COMPARATOR);
		}
		expireMinHeap.offer(new ExpireIndex(entry.key(), entry.expireGameTick(), entry.version()));
	}

	private void ensureExpireHeapReady() {
		if (expireMinHeap != null) {
			return;
		}
		expireMinHeap = new PriorityQueue<>(EXPIRE_INDEX_COMPARATOR);
		for (PendingDispatchEntry entry : pendingByKey.values()) {
			expireMinHeap.offer(new ExpireIndex(entry.key(), entry.expireGameTick(), entry.version()));
		}
	}

	private void markPendingSnapshotDirty() {
		pendingSnapshotDirty = true;
		pendingSnapshotCache = List.of();
	}

	private List<PendingDispatchEntry> pendingEntriesForSave() {
		if (pendingByKey.isEmpty()) {
			return List.of();
		}
		List<PendingDispatchEntry> entries = new ArrayList<>(pendingByKey.values());
		entries.sort(PENDING_ENTRY_COMPARATOR);
		return entries;
	}

	private long allocateNextVersion(DispatchKey key) {
		long baseline = Math.max(
			lastAcceptedVersionByKey.getOrDefault(key, 0L),
			maxIssuedVersionByKey.getOrDefault(key, 0L)
		);
		PendingDispatchEntry pendingEntry = pendingByKey.get(key);
		if (pendingEntry != null && pendingEntry.version() > baseline) {
			baseline = pendingEntry.version();
		}
		long nextVersion = baseline + 1L;
		maxIssuedVersionByKey.put(key, nextVersion);
		return nextVersion;
	}

	private void readVersionMap(ListTag listTag, Map<DispatchKey, Long> target) {
		for (Tag element : listTag) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<DispatchKey> key = parseDispatchKey(entryTag);
			if (key.isEmpty()) {
				continue;
			}
			long version = entryTag.getLong(KEY_VERSION);
			if (version <= 0L) {
				continue;
			}
			target.merge(key.get(), version, Math::max);
		}
	}

	private static ListTag writeVersionMap(Map<DispatchKey, Long> versionByKey) {
		ListTag listTag = new ListTag();
		versionByKey
			.entrySet()
			.stream()
			.filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
			.sorted(Map.Entry.comparingByKey(DISPATCH_KEY_COMPARATOR))
			.forEach(entry -> {
				CompoundTag entryTag = new CompoundTag();
				writeDispatchKey(entryTag, entry.getKey());
				entryTag.putLong(KEY_VERSION, entry.getValue());
				listTag.add(entryTag);
			});
		return listTag;
	}

	private static void writeDispatchKey(CompoundTag tag, DispatchKey key) {
		tag.putString(KEY_SOURCE_TYPE, LinkNodeSemantics.toSemanticName(key.sourceType()));
		tag.putLong(KEY_SOURCE_SERIAL, key.sourceSerial());
		tag.putString(KEY_TARGET_TYPE, LinkNodeSemantics.toSemanticName(key.targetType()));
		tag.putLong(KEY_TARGET_SERIAL, key.targetSerial());
		tag.putString(KEY_DISPATCH_KIND, key.dispatchKind().name());
	}

	private static Optional<DispatchKey> parseDispatchKey(CompoundTag tag) {
		Optional<LinkNodeType> sourceType = LinkNodeSemantics.tryParseCanonicalType(tag.getString(KEY_SOURCE_TYPE));
		Optional<LinkNodeType> targetType = LinkNodeSemantics.tryParseCanonicalType(tag.getString(KEY_TARGET_TYPE));
		if (sourceType.isEmpty() || targetType.isEmpty()) {
			return Optional.empty();
		}
		long sourceSerial = tag.getLong(KEY_SOURCE_SERIAL);
		long targetSerial = tag.getLong(KEY_TARGET_SERIAL);
		if (sourceSerial <= 0L || targetSerial <= 0L) {
			return Optional.empty();
		}
		Optional<DispatchKind> dispatchKind = DispatchKind.fromName(tag.getString(KEY_DISPATCH_KIND));
		if (dispatchKind.isEmpty()) {
			return Optional.empty();
		}
		DispatchKey key = new DispatchKey(sourceType.get(), sourceSerial, targetType.get(), targetSerial, dispatchKind.get());
		if (!LinkNodeSemantics.isAllowedForRole(key.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return Optional.empty();
		}
		if (!LinkNodeSemantics.isAllowedForRole(key.targetType(), LinkNodeSemantics.Role.TARGET)) {
			return Optional.empty();
		}
		return Optional.of(key);
	}

	private static Optional<PendingDispatchEntry> parsePendingEntry(CompoundTag tag) {
		Optional<DispatchKey> key = parseDispatchKey(tag);
		if (key.isEmpty()) {
			return Optional.empty();
		}
		ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(KEY_DIMENSION));
		if (dimensionId == null) {
			return Optional.empty();
		}
		long expireTick = tag.getLong(KEY_EXPIRE_TICK);
		long version = tag.getLong(KEY_VERSION);
		if (expireTick <= 0L || version <= 0L) {
			return Optional.empty();
		}
		ActivationMode activationMode = ActivationMode.fromName(tag.getString(KEY_ACTIVATION_MODE));
		DispatchAction dispatchAction = DispatchAction.fromName(tag.getString(KEY_DISPATCH_ACTION)).orElse(DispatchAction.UPSERT);
		int syncSignalStrength = SignalStrengths.clamp(tag.getInt(KEY_SYNC_SIGNAL_STRENGTH));
		long enqueueTick = Math.max(0L, tag.getLong(KEY_ENQUEUE_TICK));
		int enqueueSlot = Math.max(0, tag.getInt(KEY_ENQUEUE_SLOT));
		BlockPos pos = BlockPos.of(tag.getLong(KEY_POS));
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		Optional<DispatchKey> normalizedKey = normalizeLegacyActivationKey(key.get(), dispatchAction, activationMode);
		if (normalizedKey.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(
			new PendingDispatchEntry(
				normalizedKey.get(),
				dispatchAction,
				dimension,
				pos,
				activationMode,
				syncSignalStrength,
				enqueueTick,
				enqueueSlot,
				expireTick,
				version
			)
		);
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
		ACTIVATION,
		PULSE_EVENT,
		TOGGLE_EVENT,
		SYNC_SIGNAL,
		SOURCE_INVALIDATION,
		TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
		TRIGGER_SOURCE_INVALIDATION;

		private static Optional<DispatchKind> fromName(String raw) {
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
	 * 将旧版 `ACTIVATION` 持久项迁移到新的事件 kind。
	 * <p>
	 * 旧版 activation remove 与新的事件语义不兼容，读档时直接丢弃。
	 * </p>
	 */
	private static Optional<DispatchKey> normalizeLegacyActivationKey(
		DispatchKey key,
		DispatchAction dispatchAction,
		ActivationMode activationMode
	) {
		if (key == null) {
			return Optional.empty();
		}
		if (key.dispatchKind() != DispatchKind.ACTIVATION) {
			return Optional.of(key);
		}
		if (dispatchAction == DispatchAction.REMOVE) {
			return Optional.empty();
		}
		DispatchKind normalizedKind = activationMode == ActivationMode.PULSE
			? DispatchKind.PULSE_EVENT
			: DispatchKind.TOGGLE_EVENT;
		return Optional.of(new DispatchKey(key.sourceType(), key.sourceSerial(), key.targetType(), key.targetSerial(), normalizedKind));
	}

	/**
	 * 派发动作：UPSERT 写入/更新来源贡献，REMOVE 剔除来源贡献并触发回退重算。
	 */
	public enum DispatchAction {
		UPSERT,
		REMOVE;

		private static Optional<DispatchAction> fromName(String raw) {
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

	private record ExpireIndex(DispatchKey key, long expireGameTick, long version) {}
}
