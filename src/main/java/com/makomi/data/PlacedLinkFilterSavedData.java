package com.makomi.data;

import com.makomi.util.SerialParseUtil;
import com.makomi.util.SignalStrengths;
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
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 已放置过滤器持久化数据。
 * <p>
 * 保存过滤器“只要仍处于放置态就有效”的真值，并维护按维度、过滤器种类和区块键组织的倒排索引，
 * 让过滤查询不再依赖当前区块是否已加载。
 * </p>
 */
public final class PlacedLinkFilterSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_placed_link_filters";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_KIND = "kind";
	private static final String KEY_POS = "pos";
	private static final String KEY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_NODE_SET_MODE = "nodeSetMode";
	private static final String KEY_SIGNAL_THRESHOLD_SOURCE = "signalThresholdSource";
	private static final String KEY_FIXED_SIGNAL_THRESHOLD = "fixedSignalThreshold";
	private static final String KEY_SIGNAL_MODE = "signalMode";
	private static final String KEY_NEIGHBOR_SIGNAL_STRENGTH = "neighborSignalStrength";

	private static final SavedData.Factory<PlacedLinkFilterSavedData> FACTORY = new SavedData.Factory<>(
		PlacedLinkFilterSavedData::new,
		PlacedLinkFilterSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<FilterEntryKey, FilterEntry> entriesByKey = new LinkedHashMap<>();
	private final Map<ResourceKey<Level>, Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterEntryKey>>>> chunkIndex =
		new LinkedHashMap<>();

	/**
	 * 获取共享已放置过滤器实例（主世界持久化）。
	 */
	public static PlacedLinkFilterSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static PlacedLinkFilterSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
		for (Tag element : entries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<FilterEntry> parsed = parseEntry(entryTag);
			if (parsed.isEmpty()) {
				continue;
			}
			data.putEntry(parsed.get());
		}
		return data;
	}

	/**
	 * 写入或覆盖一个已放置过滤器条目。
	 *
	 * @return true 表示持久化真值发生变化
	 */
	public boolean upsert(
		LinkFilterKind filterKind,
		ResourceKey<Level> dimension,
		BlockPos filterPos,
		LinkFilterConfigSnapshot configSnapshot,
		int neighborSignalStrength
	) {
		if (filterKind == null || dimension == null || filterPos == null || configSnapshot == null) {
			return false;
		}
		FilterEntryKey key = new FilterEntryKey(dimension, filterKind, filterPos.immutable());
		return upsertInternal(key, configSnapshot, neighborSignalStrength);
	}

	/**
	 * 写入或覆盖一个已放置过滤器条目，并保留已有的邻居输入采样值。
	 * <p>
	 * 用于启动附着阶段只恢复过滤器真值、不主动触发世界邻居采样的路径。
	 * </p>
	 *
	 * @return true 表示持久化真值发生变化
	 */
	public boolean upsertPreservingNeighborSignal(
		LinkFilterKind filterKind,
		ResourceKey<Level> dimension,
		BlockPos filterPos,
		LinkFilterConfigSnapshot configSnapshot
	) {
		if (filterKind == null || dimension == null || filterPos == null || configSnapshot == null) {
			return false;
		}
		FilterEntryKey key = new FilterEntryKey(dimension, filterKind, filterPos.immutable());
		FilterEntry previous = entriesByKey.get(key);
		int preservedNeighborSignalStrength = previous == null ? 0 : previous.neighborSignalStrength();
		return upsertInternal(key, configSnapshot, preservedNeighborSignalStrength);
	}

	/**
	 * 共享的条目归一化与写入流程。
	 */
	private boolean upsertInternal(
		FilterEntryKey key,
		LinkFilterConfigSnapshot configSnapshot,
		int neighborSignalStrength
	) {
		FilterEntry normalized = new FilterEntry(
			key,
			configSnapshot,
			parseSerialExpression(configSnapshot.serialExpression()),
			SignalStrengths.clamp(neighborSignalStrength)
		);
		FilterEntry previous = entriesByKey.get(key);
		if (normalized.equals(previous)) {
			return false;
		}
		if (previous != null) {
			unindexEntry(previous);
		}
		entriesByKey.put(key, normalized);
		indexEntry(normalized);
		setDirty();
		return true;
	}

	/**
	 * 删除一个已放置过滤器条目。
	 *
	 * @return true 表示删除成功
	 */
	public boolean remove(ResourceKey<Level> dimension, LinkFilterKind filterKind, BlockPos filterPos) {
		if (dimension == null || filterKind == null || filterPos == null) {
			return false;
		}
		FilterEntry removed = entriesByKey.remove(new FilterEntryKey(dimension, filterKind, filterPos.immutable()));
		if (removed == null) {
			return false;
		}
		unindexEntry(removed);
		setDirty();
		return true;
	}

	/**
	 * 收集指定节点位置命中的过滤器运行时视图。
	 */
	public List<LinkFilterRuleEvaluator.FilterRuntimeView> collectFilters(
		ResourceKey<Level> dimension,
		BlockPos nodePos,
		LinkFilterKind filterKind
	) {
		if (dimension == null || nodePos == null || filterKind == null) {
			return List.of();
		}
		Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterEntryKey>>> kindIndex = chunkIndex.get(dimension);
		if (kindIndex == null) {
			return List.of();
		}
		Map<Long, LinkedHashSet<FilterEntryKey>> chunkIndexByKind = kindIndex.get(filterKind);
		if (chunkIndexByKind == null) {
			return List.of();
		}
		LinkedHashSet<FilterEntryKey> candidates = chunkIndexByKind.get(new ChunkPos(nodePos).toLong());
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = new ArrayList<>(candidates.size());
		for (FilterEntryKey key : candidates) {
			FilterEntry entry = entriesByKey.get(key);
			if (entry == null || !entry.covers(nodePos)) {
				continue;
			}
			activeFilters.add(entry.toRuntimeView());
		}
		return activeFilters.isEmpty() ? List.of() : List.copyOf(activeFilters);
	}

	/**
	 * 返回当前已放置过滤器条目快照。
	 */
	public List<FilterEntry> entriesSnapshot() {
		if (entriesByKey.isEmpty()) {
			return List.of();
		}
		List<FilterEntry> entries = new ArrayList<>(entriesByKey.values());
		entries.sort(
			Comparator
				.comparing((FilterEntry entry) -> entry.key().filterKind().token())
				.thenComparing(entry -> entry.key().dimension().location().toString())
				.thenComparingLong(entry -> entry.key().filterPos().asLong())
		);
		return List.copyOf(entries);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag entries = new ListTag();
		for (FilterEntry entry : entriesSnapshot()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putString(KEY_DIMENSION, entry.key().dimension().location().toString());
			entryTag.putString(KEY_KIND, entry.key().filterKind().token());
			entryTag.putLong(KEY_POS, entry.key().filterPos().asLong());
			entryTag.putString(KEY_SERIAL_EXPRESSION, entry.configSnapshot().serialExpression());
			entryTag.putString(KEY_NODE_SET_MODE, entry.configSnapshot().nodeSetMode().token());
			entryTag.putString(KEY_SIGNAL_THRESHOLD_SOURCE, entry.configSnapshot().signalThresholdSource().token());
			entryTag.putInt(KEY_FIXED_SIGNAL_THRESHOLD, entry.configSnapshot().fixedSignalThreshold());
			entryTag.putString(KEY_SIGNAL_MODE, entry.configSnapshot().signalMode().token());
			entryTag.putInt(KEY_NEIGHBOR_SIGNAL_STRENGTH, entry.neighborSignalStrength());
			entries.add(entryTag);
		}
		tag.put(KEY_ENTRIES, entries);
		return tag;
	}

	/**
	 * 载入条目时写入主表与倒排索引。
	 */
	private void putEntry(FilterEntry entry) {
		entriesByKey.put(entry.key(), entry);
		indexEntry(entry);
	}

	/**
	 * 建立一个过滤器条目的区块倒排索引。
	 */
	private void indexEntry(FilterEntry entry) {
		for (long coveredChunkKey : computeCoveredChunkKeys(entry.key().filterPos())) {
			chunkIndex
				.computeIfAbsent(entry.key().dimension(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(entry.key().filterKind(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(coveredChunkKey, ignored -> new LinkedHashSet<>())
				.add(entry.key());
		}
	}

	/**
	 * 从区块倒排索引中移除一个过滤器条目。
	 */
	private void unindexEntry(FilterEntry entry) {
		Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterEntryKey>>> kindIndex = chunkIndex.get(entry.key().dimension());
		if (kindIndex == null) {
			return;
		}
		Map<Long, LinkedHashSet<FilterEntryKey>> chunkIndexByKind = kindIndex.get(entry.key().filterKind());
		if (chunkIndexByKind == null) {
			return;
		}
		for (long coveredChunkKey : computeCoveredChunkKeys(entry.key().filterPos())) {
			LinkedHashSet<FilterEntryKey> registrations = chunkIndexByKind.get(coveredChunkKey);
			if (registrations == null) {
				continue;
			}
			registrations.remove(entry.key());
			if (registrations.isEmpty()) {
				chunkIndexByKind.remove(coveredChunkKey);
			}
		}
		if (chunkIndexByKind.isEmpty()) {
			kindIndex.remove(entry.key().filterKind());
		}
		if (kindIndex.isEmpty()) {
			chunkIndex.remove(entry.key().dimension());
		}
	}

	/**
	 * 解析单个持久化条目。
	 */
	private static Optional<FilterEntry> parseEntry(CompoundTag entryTag) {
		ResourceLocation dimensionId = ResourceLocation.tryParse(entryTag.getString(KEY_DIMENSION));
		if (dimensionId == null) {
			return Optional.empty();
		}
		Optional<LinkFilterKind> filterKind = LinkFilterKind.tryParseToken(entryTag.getString(KEY_KIND));
		if (filterKind.isEmpty()) {
			return Optional.empty();
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		BlockPos filterPos = BlockPos.of(entryTag.getLong(KEY_POS));
		LinkFilterConfigSnapshot configSnapshot = new LinkFilterConfigSnapshot(
			entryTag.getString(KEY_SERIAL_EXPRESSION),
			LinkFilterNodeSetMode.tryParseToken(entryTag.getString(KEY_NODE_SET_MODE)).orElse(LinkFilterNodeSetMode.DISABLED),
			LinkFilterSignalThresholdSource
				.tryParseToken(entryTag.getString(KEY_SIGNAL_THRESHOLD_SOURCE))
				.orElse(LinkFilterSignalThresholdSource.FIXED_INPUT),
			entryTag.getInt(KEY_FIXED_SIGNAL_THRESHOLD),
			LinkFilterSignalMode.tryParseToken(entryTag.getString(KEY_SIGNAL_MODE)).orElse(LinkFilterSignalMode.DISABLED)
		);
		FilterEntryKey key = new FilterEntryKey(dimension, filterKind.get(), filterPos);
		return Optional.of(
			new FilterEntry(
				key,
				configSnapshot,
				parseSerialExpression(configSnapshot.serialExpression()),
				SignalStrengths.clamp(entryTag.getInt(KEY_NEIGHBOR_SIGNAL_STRENGTH))
			)
		);
	}

	/**
	 * 解析序号表达式，统一过滤非法与重复项。
	 */
	private static Set<Long> parseSerialExpression(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, 0);
		if (parseResult.orderedTargets().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(parseResult.orderedTargets()));
	}

	/**
	 * 计算过滤器立方域覆盖到的全部区块键。
	 */
	private static List<Long> computeCoveredChunkKeys(BlockPos filterPos) {
		int minChunkX = SectionPos.blockToSectionCoord(filterPos.getX() - LinkDispatchFilterService.FILTER_RADIUS);
		int maxChunkX = SectionPos.blockToSectionCoord(filterPos.getX() + LinkDispatchFilterService.FILTER_RADIUS);
		int minChunkZ = SectionPos.blockToSectionCoord(filterPos.getZ() - LinkDispatchFilterService.FILTER_RADIUS);
		int maxChunkZ = SectionPos.blockToSectionCoord(filterPos.getZ() + LinkDispatchFilterService.FILTER_RADIUS);
		List<Long> coveredChunkKeys = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				coveredChunkKeys.add(new ChunkPos(chunkX, chunkZ).toLong());
			}
		}
		return List.copyOf(coveredChunkKeys);
	}

	/**
	 * 过滤器条目标识。
	 */
	private record FilterEntryKey(ResourceKey<Level> dimension, LinkFilterKind filterKind, BlockPos filterPos) {}

	/**
	 * 已放置过滤器真值条目。
	 */
	public record FilterEntry(
		FilterEntryKey key,
		LinkFilterConfigSnapshot configSnapshot,
		Set<Long> serials,
		int neighborSignalStrength
	) {
		public FilterEntry {
			configSnapshot = configSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : configSnapshot;
			serials = Set.copyOf(serials == null ? Set.of() : serials);
			neighborSignalStrength = SignalStrengths.clamp(neighborSignalStrength);
		}

		/**
		 * @return 过滤器所在维度
		 */
		public ResourceKey<Level> dimension() {
			return key.dimension();
		}

		/**
		 * @return 过滤器运行时种类
		 */
		public LinkFilterKind filterKind() {
			return key.filterKind();
		}

		/**
		 * @return 过滤器方块坐标
		 */
		public BlockPos filterPos() {
			return key.filterPos();
		}

		/**
		 * @return 当前条目是否依赖邻居输入作为阈值来源
		 */
		public boolean usesNeighborSignalThreshold() {
			return configSnapshot.signalThresholdSource() == LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT;
		}

		/**
		 * 判断当前过滤器立方域是否覆盖目标节点位置。
		 */
		boolean covers(BlockPos targetPos) {
			if (targetPos == null) {
				return false;
			}
			return Math.abs(targetPos.getX() - key.filterPos().getX()) <= LinkDispatchFilterService.FILTER_RADIUS
				&& Math.abs(targetPos.getY() - key.filterPos().getY()) <= LinkDispatchFilterService.FILTER_RADIUS
				&& Math.abs(targetPos.getZ() - key.filterPos().getZ()) <= LinkDispatchFilterService.FILTER_RADIUS;
		}

		/**
		 * 转为规则求值器运行时视图。
		 */
		LinkFilterRuleEvaluator.FilterRuntimeView toRuntimeView() {
			return new LinkFilterRuleEvaluator.FilterRuntimeView(
				configSnapshot.nodeSetMode(),
				serials,
				configSnapshot.signalThresholdSource(),
				configSnapshot.fixedSignalThreshold(),
				configSnapshot.signalMode(),
				neighborSignalStrength
			);
		}
	}
}
