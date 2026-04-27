package com.makomi.data;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 待退役队列持久化数据。
 * <p>
 * 保存节点待退役任务，避免仅内存队列在重启后丢失。
 * </p>
 */
public final class PendingRetireQueueSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_pending_retire_queue";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_TYPE = "type";
	private static final String KEY_SERIAL = "serial";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_POS = "pos";
	private static final String KEY_EXPIRE_TICK = "expireTick";

	private static final Codec<PendingRetireQueueSavedData> CODEC = CompoundTag.CODEC.xmap(
		PendingRetireQueueSavedData::load,
		PendingRetireQueueSavedData::toTag
	);
	private static final SavedDataType<PendingRetireQueueSavedData> TYPE = new SavedDataType<>(
		DATA_NAME,
		PendingRetireQueueSavedData::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<PendingKey, PendingRetireEntry> entriesByKey = new HashMap<>();

	/**
	 * 获取共享待退役队列持久化实例。
	 */
	public static PendingRetireQueueSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	private static PendingRetireQueueSavedData load(CompoundTag tag) {
		PendingRetireQueueSavedData data = new PendingRetireQueueSavedData();
		ListTag entries = tag.getListOrEmpty(KEY_ENTRIES);
		for (Tag element : entries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<PendingRetireEntry> parsed = parseEntry(entryTag);
			if (parsed.isEmpty()) {
				continue;
			}
			PendingRetireEntry entry = parsed.get();
			data.entriesByKey.put(new PendingKey(entry.nodeType(), entry.serial()), entry);
		}
		return data;
	}

	/**
	 * 写入或覆盖待退役条目。
	 *
	 * @param nodeType 节点类型
	 * @param serial 节点序号
	 * @param dimension 节点维度
	 * @param pos 节点坐标
	 * @param expireTick 到期游戏刻
	 * @return 是否发生状态变化
	 */
	public boolean upsert(
		LinkNodeType nodeType,
		long serial,
		ResourceKey<Level> dimension,
		BlockPos pos,
		long expireTick
	) {
		if (nodeType == null || serial <= 0L || dimension == null || pos == null || expireTick < 0L) {
			return false;
		}
		PendingRetireEntry normalized = new PendingRetireEntry(nodeType, serial, dimension, pos.immutable(), expireTick);
		PendingKey key = new PendingKey(nodeType, serial);
		PendingRetireEntry previous = entriesByKey.put(key, normalized);
		boolean changed = !normalized.equals(previous);
		if (changed) {
			setDirty();
		}
		return changed;
	}

	/**
	 * 删除待退役条目。
	 *
	 * @param nodeType 节点类型
	 * @param serial 节点序号
	 * @return 是否删除成功
	 */
	public boolean remove(LinkNodeType nodeType, long serial) {
		if (nodeType == null || serial <= 0L) {
			return false;
		}
		PendingRetireEntry removed = entriesByKey.remove(new PendingKey(nodeType, serial));
		if (removed == null) {
			return false;
		}
		setDirty();
		return true;
	}

	/**
	 * 清空全部待退役条目。
	 */
	public void clear() {
		if (entriesByKey.isEmpty()) {
			return;
		}
		entriesByKey.clear();
		setDirty();
	}

	/**
	 * 返回当前条目快照。
	 */
	public List<PendingRetireEntry> entriesSnapshot() {
		if (entriesByKey.isEmpty()) {
			return List.of();
		}
		List<PendingRetireEntry> entries = new ArrayList<>(entriesByKey.values());
		entries.sort(
			Comparator
				.comparing((PendingRetireEntry entry) -> entry.nodeType().name())
				.thenComparingLong(PendingRetireEntry::serial)
		);
		return List.copyOf(entries);
	}

	private CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		ListTag entries = new ListTag();
		for (PendingRetireEntry entry : entriesSnapshot()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putString(KEY_TYPE, LinkNodeSemantics.toSemanticName(entry.nodeType()));
			entryTag.putLong(KEY_SERIAL, entry.serial());
			entryTag.putString(KEY_DIMENSION, entry.dimension().identifier().toString());
			entryTag.putLong(KEY_POS, entry.pos().asLong());
			entryTag.putLong(KEY_EXPIRE_TICK, entry.expireTick());
			entries.add(entryTag);
		}
		tag.put(KEY_ENTRIES, entries);
		return tag;
	}

	/**
	 * 解析持久化条目。
	 */
	private static Optional<PendingRetireEntry> parseEntry(CompoundTag entryTag) {
		Optional<LinkNodeType> nodeType = LinkNodeSemantics.tryParseCanonicalType(entryTag.getStringOr(KEY_TYPE, ""));
		if (nodeType.isEmpty()) {
			return Optional.empty();
		}
		long serial = entryTag.getLongOr(KEY_SERIAL, 0L);
		if (serial <= 0L) {
			return Optional.empty();
		}
		Identifier dimensionId = Identifier.tryParse(entryTag.getStringOr(KEY_DIMENSION, ""));
		if (dimensionId == null) {
			return Optional.empty();
		}
		long expireTick = entryTag.getLongOr(KEY_EXPIRE_TICK, -1L);
		if (expireTick < 0L) {
			return Optional.empty();
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		BlockPos pos = BlockPos.of(entryTag.getLongOr(KEY_POS, 0L));
		return Optional.of(new PendingRetireEntry(nodeType.get(), serial, dimension, pos, expireTick));
	}

	/**
	 * 待退役键：节点类型 + 节点序号。
	 */
	private record PendingKey(LinkNodeType nodeType, long serial) {}

	/**
	 * 待退役条目快照。
	 *
	 * @param nodeType 节点类型
	 * @param serial 节点序号
	 * @param dimension 节点维度
	 * @param pos 节点坐标
	 * @param expireTick 到期游戏刻
	 */
	public record PendingRetireEntry(
		LinkNodeType nodeType,
		long serial,
		ResourceKey<Level> dimension,
		BlockPos pos,
		long expireTick
	) {}
}
