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
 * 每个区块激活器独立保存其节点集真值，并维护：
 * </p>
 * <ul>
 * <li>区块激活器主表；</li>
 * <li>`triggerSource serial -> activatorKey` 反向索引；</li>
 * <li>激活态下 `forceLoad/resident` 两套聚合贡献计数。</li>
 * </ul>
 * <p>
 * 普通区块卸载不会删除该真值；只有物理破坏时才移除条目。
 * </p>
 */
public final class PlacedChunkActivatorSavedData extends SavedData {
	public static final int MAX_TRIGGER_SOURCE_COUNT = 32;

	private static final String DATA_NAME = "redstonelink_placed_chunk_activators";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_POS = "pos";
	private static final String KEY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_MODE = "mode";
	private static final String KEY_DISPLAY_ALIAS = "displayAlias";
	private static final String KEY_ACTIVE = "active";

	private static final SavedData.Factory<PlacedChunkActivatorSavedData> FACTORY = new SavedData.Factory<>(
		PlacedChunkActivatorSavedData::new,
		PlacedChunkActivatorSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<ActivatorEntryKey, ActivatorEntry> entriesByKey = new LinkedHashMap<>();
	private final Map<Long, LinkedHashSet<ActivatorEntryKey>> triggerSourceSerialIndex = new LinkedHashMap<>();
	private final Map<Long, Integer> forceLoadSourceRefCounts = new LinkedHashMap<>();
	private final Map<Long, Integer> residentSourceRefCounts = new LinkedHashMap<>();
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
		ChunkActivatorConfigSnapshot configSnapshot,
		String displayAlias,
		boolean active
	) {
		if (dimension == null || activatorPos == null || configSnapshot == null) {
			return false;
		}
		ActivatorEntryKey key = new ActivatorEntryKey(dimension, activatorPos.immutable());
		ActivatorEntry normalized = new ActivatorEntry(
			key,
			configSnapshot,
			parseSerialExpression(configSnapshot.serialExpression()),
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
	 * 判断指定 `triggerSource` 当前是否被激活态区块激活器纳入强加载集合。
	 */
	public boolean containsActiveForceLoadTriggerSource(long serial) {
		return serial > 0L && forceLoadSourceRefCounts.getOrDefault(serial, 0) > 0;
	}

	/**
	 * 判断指定 `triggerSource` 当前是否被激活态区块激活器纳入 resident 集合。
	 */
	public boolean containsActiveResidentTriggerSource(long serial) {
		return serial > 0L && residentSourceRefCounts.getOrDefault(serial, 0) > 0;
	}

	/**
	 * 当前是否仍存在 resident 区块激活器条目。
	 */
	public boolean hasResidents() {
		return !residentSourceRefCounts.isEmpty();
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
	 * 遍历当前 resident `triggerSource` 序号。
	 */
	public void forEachResidentTriggerSourceSerial(TriggerSourceSerialConsumer consumer) {
		if (consumer == null || residentSourceRefCounts.isEmpty()) {
			return;
		}
		for (Long serial : residentSourceRefCounts.keySet()) {
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
			entryTag.putString(KEY_SERIAL_EXPRESSION, entry.configSnapshot().serialExpression());
			entryTag.putString(KEY_MODE, entry.configSnapshot().mode().token());
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
		for (Long serial : entry.serials()) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			triggerSourceSerialIndex.computeIfAbsent(serial, ignored -> new LinkedHashSet<>()).add(entry.key());
		}
	}

	private void unindexEntry(ActivatorEntry entry) {
		for (Long serial : entry.serials()) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			LinkedHashSet<ActivatorEntryKey> registrations = triggerSourceSerialIndex.get(serial);
			if (registrations == null) {
				continue;
			}
			registrations.remove(entry.key());
			if (registrations.isEmpty()) {
				triggerSourceSerialIndex.remove(serial);
			}
		}
	}

	private boolean applyContribution(ActivatorEntry entry) {
		if (entry == null || !entry.active()) {
			return false;
		}
		boolean residentChanged = false;
		for (Long serial : entry.serials()) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			incrementRefCount(forceLoadSourceRefCounts, serial);
			if (entry.configSnapshot().mode().contributesResident()) {
				residentChanged |= incrementRefCount(residentSourceRefCounts, serial);
			}
		}
		return residentChanged;
	}

	private boolean unapplyContribution(ActivatorEntry entry) {
		if (entry == null || !entry.active()) {
			return false;
		}
		boolean residentChanged = false;
		for (Long serial : entry.serials()) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			decrementRefCount(forceLoadSourceRefCounts, serial);
			if (entry.configSnapshot().mode().contributesResident()) {
				residentChanged |= decrementRefCount(residentSourceRefCounts, serial);
			}
		}
		return residentChanged;
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
		ChunkActivatorConfigSnapshot configSnapshot = new ChunkActivatorConfigSnapshot(
			entryTag.getString(KEY_SERIAL_EXPRESSION),
			ChunkActivatorMode.tryParseToken(entryTag.getString(KEY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		String displayAlias = entryTag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)
			? NodeAliasDisplayUtil.normalizeAlias(entryTag.getString(KEY_DISPLAY_ALIAS))
			: "";
		boolean active = entryTag.getBoolean(KEY_ACTIVE);
		ActivatorEntryKey key = new ActivatorEntryKey(dimension, activatorPos);
		return Optional.of(new ActivatorEntry(key, configSnapshot, parseSerialExpression(configSnapshot.serialExpression()), displayAlias, active));
	}

	private static Set<Long> parseSerialExpression(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawExpression,
			MAX_TRIGGER_SOURCE_COUNT
		);
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
		ChunkActivatorConfigSnapshot configSnapshot,
		Set<Long> serials,
		String displayAlias,
		boolean active
	) {
		public ActivatorEntry {
			configSnapshot = configSnapshot == null
				? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
				: configSnapshot;
			serials = Set.copyOf(serials == null ? Set.of() : serials);
			displayAlias = NodeAliasDisplayUtil.normalizeAlias(displayAlias);
		}

		boolean sameSerialIndex(ActivatorEntry other) {
			return other != null && serials.equals(other.serials());
		}

		boolean sameContribution(ActivatorEntry other) {
			return other != null
				&& active == other.active()
				&& configSnapshot.mode() == other.configSnapshot().mode()
				&& serials.equals(other.serials());
		}
	}

	/**
	 * resident `triggerSource` 序号遍历回调。
	 */
	@FunctionalInterface
	public interface TriggerSourceSerialConsumer {
		void accept(long serial);
	}
}
