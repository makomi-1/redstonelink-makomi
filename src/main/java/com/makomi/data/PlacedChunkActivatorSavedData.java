package com.makomi.data;

import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
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
 * 已放置区块激活器持久化数据。
 * <p>
 * 每个区块激活器独立保存其 `triggerSource/core` 两套节点集真值，并维护：
 * </p>
 * <ul>
 * <li>区块激活器主表；</li>
 * <li>`triggerSource/core serial -> activatorKey` 反向索引；</li>
 * <li>激活态下按当前作用类型生效的 `forceLoad/resident` 聚合贡献计数。</li>
 * </ul>
 * <p>
 * 普通区块卸载不会删除该真值；只有物理破坏时才移除条目。
 * </p>
 */
public final class PlacedChunkActivatorSavedData extends SavedData {
	public static final int MAX_NODE_SET_SIZE = 32;

	private static final String DATA_NAME = "redstonelink_placed_chunk_activators";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_POS = "pos";
	private static final String KEY_ACTIVE_TYPE = "activeType";
	private static final String KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION = "triggerSourceSerialExpression";
	private static final String KEY_TRIGGER_SOURCE_MODE = "triggerSourceMode";
	private static final String KEY_CORE_SERIAL_EXPRESSION = "coreSerialExpression";
	private static final String KEY_CORE_MODE = "coreMode";
	private static final String KEY_DISPLAY_ALIAS = "displayAlias";
	private static final String KEY_ACTIVE = "active";
	private static final String KEY_LEGACY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_LEGACY_MODE = "mode";

	private static final SavedData.Factory<PlacedChunkActivatorSavedData> FACTORY = new SavedData.Factory<>(
		PlacedChunkActivatorSavedData::new,
		PlacedChunkActivatorSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<ActivatorEntryKey, ActivatorEntry> entriesByKey = new LinkedHashMap<>();
	private final Map<Long, LinkedHashSet<ActivatorEntryKey>> triggerSourceSerialIndex = new LinkedHashMap<>();
	private final Map<Long, LinkedHashSet<ActivatorEntryKey>> coreSerialIndex = new LinkedHashMap<>();
	private final Map<Long, Integer> forceLoadTriggerSourceRefCounts = new LinkedHashMap<>();
	private final Map<Long, Integer> forceLoadCoreRefCounts = new LinkedHashMap<>();
	private final Map<Long, Integer> residentTriggerSourceRefCounts = new LinkedHashMap<>();
	private final Map<Long, Integer> residentCoreRefCounts = new LinkedHashMap<>();
	private long residentStateVersion;

	/**
	 * 获取共享已放置区块激活器实例（主世界持久化）。
	 */
	public static PlacedChunkActivatorSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static PlacedChunkActivatorSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		PlacedChunkActivatorSavedData data = new PlacedChunkActivatorSavedData();
		ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
		for (Tag element : entries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<ActivatorEntry> parsed = parseEntry(entryTag);
			if (parsed.isEmpty()) {
				continue;
			}
			data.putEntry(parsed.get());
		}
		return data;
	}

	/**
	 * 写入或覆盖一个已放置区块激活器条目。
	 *
	 * @return true 表示持久化真值发生变化
	 */
	public boolean upsert(
		ResourceKey<Level> dimension,
		BlockPos activatorPos,
		ChunkActivatorConfigStateSnapshot configStateSnapshot,
		String displayAlias,
		boolean active
	) {
		if (dimension == null || activatorPos == null || configStateSnapshot == null) {
			return false;
		}
		ActivatorEntryKey key = new ActivatorEntryKey(dimension, activatorPos.immutable());
		ActivatorEntry normalized = new ActivatorEntry(
			key,
			configStateSnapshot,
			parseSerialExpression(configStateSnapshot.triggerSourceConfig().serialExpression()),
			parseSerialExpression(configStateSnapshot.coreConfig().serialExpression()),
			NodeAliasDisplayUtil.normalizeAlias(displayAlias),
			active
		);
		ActivatorEntry previous = entriesByKey.get(key);
		if (normalized.equals(previous)) {
			return false;
		}
		boolean residentChanged = false;
		if (previous != null) {
			if (!previous.sameSerialIndex(normalized)) {
				unindexEntry(previous);
			}
			if (!previous.sameContribution(normalized)) {
				residentChanged |= unapplyContribution(previous);
			}
		}
		entriesByKey.put(key, normalized);
		if (previous == null || !previous.sameSerialIndex(normalized)) {
			indexEntry(normalized);
		}
		if (previous == null || !previous.sameContribution(normalized)) {
			residentChanged |= applyContribution(normalized);
		}
		if (residentChanged) {
			bumpResidentStateVersion();
		}
		setDirty();
		return true;
	}

	/**
	 * 删除一个已放置区块激活器条目。
	 *
	 * @return true 表示删除成功
	 */
	public boolean remove(ResourceKey<Level> dimension, BlockPos activatorPos) {
		if (dimension == null || activatorPos == null) {
			return false;
		}
		ActivatorEntry removed = entriesByKey.remove(new ActivatorEntryKey(dimension, activatorPos.immutable()));
		if (removed == null) {
			return false;
		}
		unindexEntry(removed);
		boolean residentChanged = unapplyContribution(removed);
		if (residentChanged) {
			bumpResidentStateVersion();
		}
		setDirty();
		return true;
	}

	/**
	 * 判断指定作用类型当前是否被激活态区块激活器纳入强加载集合。
	 */
	public boolean containsActiveForceLoad(LinkNodeType type, long serial) {
		return serial > 0L && refCountBucket(type, false).getOrDefault(serial, 0) > 0;
	}

	/**
	 * 判断指定作用类型当前是否被激活态区块激活器纳入 resident 集合。
	 */
	public boolean containsActiveResident(LinkNodeType type, long serial) {
		return serial > 0L && refCountBucket(type, true).getOrDefault(serial, 0) > 0;
	}

	/**
	 * 当前是否仍存在 resident 区块激活器条目。
	 */
	public boolean hasResidents() {
		return !residentTriggerSourceRefCounts.isEmpty() || !residentCoreRefCounts.isEmpty();
	}

	/**
	 * resident 集合状态版本。
	 * <p>
	 * 仅在 resident 聚合集合本身发生变化时递增。
	 * </p>
	 */
	public long residentStateVersion() {
		return residentStateVersion;
	}

	/**
	 * 遍历当前 resident 指定作用类型序号。
	 */
	public void forEachResidentSerial(LinkNodeType type, LongConsumer consumer) {
		Map<Long, Integer> bucket = refCountBucket(type, true);
		if (consumer == null || bucket.isEmpty()) {
			return;
		}
		for (Long serial : bucket.keySet()) {
			if (serial != null && serial > 0L) {
				consumer.accept(serial);
			}
		}
	}

	/**
	 * 查询指定位置的区块激活器条目。
	 */
	public Optional<ActivatorEntry> findEntry(ResourceKey<Level> dimension, BlockPos activatorPos) {
		if (dimension == null || activatorPos == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(entriesByKey.get(new ActivatorEntryKey(dimension, activatorPos.immutable())));
	}

	/**
	 * 返回当前已放置区块激活器条目快照。
	 */
	public List<ActivatorEntry> entriesSnapshot() {
		if (entriesByKey.isEmpty()) {
			return List.of();
		}
		List<ActivatorEntry> entries = new ArrayList<>(entriesByKey.values());
		entries.sort(
			Comparator
				.comparing((ActivatorEntry entry) -> entry.key().dimension().location().toString())
				.thenComparingLong(entry -> entry.key().activatorPos().asLong())
		);
		return List.copyOf(entries);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag entries = new ListTag();
		for (ActivatorEntry entry : entriesSnapshot()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putString(KEY_DIMENSION, entry.key().dimension().location().toString());
			entryTag.putLong(KEY_POS, entry.key().activatorPos().asLong());
			entryTag.putString(KEY_ACTIVE_TYPE, ChunkActivatorConfigStateSnapshot.toTypeToken(entry.configStateSnapshot().activeType()));
			entryTag.putString(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, entry.configStateSnapshot().triggerSourceConfig().serialExpression());
			entryTag.putString(KEY_TRIGGER_SOURCE_MODE, entry.configStateSnapshot().triggerSourceConfig().mode().token());
			entryTag.putString(KEY_CORE_SERIAL_EXPRESSION, entry.configStateSnapshot().coreConfig().serialExpression());
			entryTag.putString(KEY_CORE_MODE, entry.configStateSnapshot().coreConfig().mode().token());
			if (!entry.displayAlias().isBlank()) {
				entryTag.putString(KEY_DISPLAY_ALIAS, entry.displayAlias());
			}
			entryTag.putBoolean(KEY_ACTIVE, entry.active());
			entries.add(entryTag);
		}
		tag.put(KEY_ENTRIES, entries);
		return tag;
	}

	private void putEntry(ActivatorEntry entry) {
		entriesByKey.put(entry.key(), entry);
		indexEntry(entry);
		applyContribution(entry);
	}

	private void indexEntry(ActivatorEntry entry) {
		indexSerials(entry.triggerSourceSerials(), triggerSourceSerialIndex, entry.key());
		indexSerials(entry.coreSerials(), coreSerialIndex, entry.key());
	}

	private void unindexEntry(ActivatorEntry entry) {
		unindexSerials(entry.triggerSourceSerials(), triggerSourceSerialIndex, entry.key());
		unindexSerials(entry.coreSerials(), coreSerialIndex, entry.key());
	}

	private boolean applyContribution(ActivatorEntry entry) {
		if (entry == null || !entry.active()) {
			return false;
		}
		boolean residentChanged = false;
		LinkNodeType activeType = entry.configStateSnapshot().activeType();
		for (Long serial : entry.serialsFor(activeType)) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			incrementRefCount(refCountBucket(activeType, false), serial);
			if (entry.configStateSnapshot().activeConfig().mode().contributesResident()) {
				residentChanged |= incrementRefCount(refCountBucket(activeType, true), serial);
			}
		}
		return residentChanged;
	}

	private boolean unapplyContribution(ActivatorEntry entry) {
		if (entry == null || !entry.active()) {
			return false;
		}
		boolean residentChanged = false;
		LinkNodeType activeType = entry.configStateSnapshot().activeType();
		for (Long serial : entry.serialsFor(activeType)) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			decrementRefCount(refCountBucket(activeType, false), serial);
			if (entry.configStateSnapshot().activeConfig().mode().contributesResident()) {
				residentChanged |= decrementRefCount(refCountBucket(activeType, true), serial);
			}
		}
		return residentChanged;
	}

	private Map<Long, Integer> refCountBucket(LinkNodeType type, boolean resident) {
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		if (resident) {
			return normalizedType == LinkNodeType.CORE ? residentCoreRefCounts : residentTriggerSourceRefCounts;
		}
		return normalizedType == LinkNodeType.CORE ? forceLoadCoreRefCounts : forceLoadTriggerSourceRefCounts;
	}

	private static void indexSerials(
		Set<Long> serials,
		Map<Long, LinkedHashSet<ActivatorEntryKey>> indexBucket,
		ActivatorEntryKey key
	) {
		for (Long serial : serials) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			indexBucket.computeIfAbsent(serial, ignored -> new LinkedHashSet<>()).add(key);
		}
	}

	private static void unindexSerials(
		Set<Long> serials,
		Map<Long, LinkedHashSet<ActivatorEntryKey>> indexBucket,
		ActivatorEntryKey key
	) {
		for (Long serial : serials) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			LinkedHashSet<ActivatorEntryKey> registrations = indexBucket.get(serial);
			if (registrations == null) {
				continue;
			}
			registrations.remove(key);
			if (registrations.isEmpty()) {
				indexBucket.remove(serial);
			}
		}
	}

	private void bumpResidentStateVersion() {
		residentStateVersion++;
	}

	private static Optional<ActivatorEntry> parseEntry(CompoundTag entryTag) {
		ResourceLocation dimensionId = ResourceLocation.tryParse(entryTag.getString(KEY_DIMENSION));
		if (dimensionId == null) {
			return Optional.empty();
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		BlockPos activatorPos = BlockPos.of(entryTag.getLong(KEY_POS));
		ChunkActivatorConfigSnapshot legacyConfig = new ChunkActivatorConfigSnapshot(
			entryTag.contains(KEY_LEGACY_SERIAL_EXPRESSION, Tag.TAG_STRING) ? entryTag.getString(KEY_LEGACY_SERIAL_EXPRESSION) : "",
			ChunkActivatorMode.tryParseToken(entryTag.getString(KEY_LEGACY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		ChunkActivatorConfigStateSnapshot configStateSnapshot = new ChunkActivatorConfigStateSnapshot(
			ChunkActivatorConfigStateSnapshot.tryParseTypeToken(
				entryTag.contains(KEY_ACTIVE_TYPE, Tag.TAG_STRING) ? entryTag.getString(KEY_ACTIVE_TYPE) : ""
			).orElse(LinkNodeType.TRIGGER_SOURCE),
			new ChunkActivatorConfigSnapshot(
				entryTag.contains(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, Tag.TAG_STRING)
					? entryTag.getString(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION)
					: legacyConfig.serialExpression(),
				ChunkActivatorMode
					.tryParseToken(
						entryTag.contains(KEY_TRIGGER_SOURCE_MODE, Tag.TAG_STRING)
							? entryTag.getString(KEY_TRIGGER_SOURCE_MODE)
							: legacyConfig.mode().token()
					)
					.orElse(legacyConfig.mode())
			),
			new ChunkActivatorConfigSnapshot(
				entryTag.contains(KEY_CORE_SERIAL_EXPRESSION, Tag.TAG_STRING)
					? entryTag.getString(KEY_CORE_SERIAL_EXPRESSION)
					: "",
				ChunkActivatorMode
					.tryParseToken(
						entryTag.contains(KEY_CORE_MODE, Tag.TAG_STRING)
							? entryTag.getString(KEY_CORE_MODE)
							: ChunkActivatorMode.FORCE_LOAD.token()
					)
					.orElse(ChunkActivatorMode.FORCE_LOAD)
			)
		);
		String displayAlias = entryTag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)
			? NodeAliasDisplayUtil.normalizeAlias(entryTag.getString(KEY_DISPLAY_ALIAS))
			: "";
		boolean active = entryTag.getBoolean(KEY_ACTIVE);
		ActivatorEntryKey key = new ActivatorEntryKey(dimension, activatorPos);
		return Optional.of(
			new ActivatorEntry(
				key,
				configStateSnapshot,
				parseSerialExpression(configStateSnapshot.triggerSourceConfig().serialExpression()),
				parseSerialExpression(configStateSnapshot.coreConfig().serialExpression()),
				displayAlias,
				active
			)
		);
	}

	private static Set<Long> parseSerialExpression(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, MAX_NODE_SET_SIZE);
		if (parseResult.orderedTargets().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(parseResult.orderedTargets()));
	}

	private static boolean incrementRefCount(Map<Long, Integer> bucket, long serial) {
		int current = bucket.getOrDefault(serial, 0);
		bucket.put(serial, current + 1);
		return current == 0;
	}

	private static boolean decrementRefCount(Map<Long, Integer> bucket, long serial) {
		Integer current = bucket.get(serial);
		if (current == null || current <= 0) {
			return false;
		}
		if (current == 1) {
			bucket.remove(serial);
			return true;
		}
		bucket.put(serial, current - 1);
		return false;
	}

	private record ActivatorEntryKey(ResourceKey<Level> dimension, BlockPos activatorPos) {}

	/**
	 * 已放置区块激活器真值条目。
	 */
	public record ActivatorEntry(
		ActivatorEntryKey key,
		ChunkActivatorConfigStateSnapshot configStateSnapshot,
		Set<Long> triggerSourceSerials,
		Set<Long> coreSerials,
		String displayAlias,
		boolean active
	) {
		public ActivatorEntry {
			configStateSnapshot = configStateSnapshot == null
				? new ChunkActivatorConfigStateSnapshot(null, null, null)
				: configStateSnapshot;
			triggerSourceSerials = Set.copyOf(triggerSourceSerials == null ? Set.of() : triggerSourceSerials);
			coreSerials = Set.copyOf(coreSerials == null ? Set.of() : coreSerials);
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
		}

		/**
		 * 按作用类型读取当前条目的节点集。
		 */
		public Set<Long> serialsFor(LinkNodeType type) {
			return ChunkActivatorConfigStateSnapshot.normalizeType(type) == LinkNodeType.CORE ? coreSerials : triggerSourceSerials;
		}

		boolean sameSerialIndex(ActivatorEntry other) {
			return other != null
				&& triggerSourceSerials.equals(other.triggerSourceSerials())
				&& coreSerials.equals(other.coreSerials());
		}

		boolean sameContribution(ActivatorEntry other) {
			return other != null
				&& active == other.active()
				&& configStateSnapshot.activeType() == other.configStateSnapshot().activeType()
				&& configStateSnapshot.activeConfig().mode() == other.configStateSnapshot().activeConfig().mode()
				&& serialsFor(configStateSnapshot.activeType()).equals(other.serialsFor(other.configStateSnapshot().activeType()));
		}
	}
}
