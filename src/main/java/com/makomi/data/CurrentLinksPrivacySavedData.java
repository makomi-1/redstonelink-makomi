package com.makomi.data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * “当前连接”保密名单持久化数据。
 * <p>
 * 按 triggerSource/core 语义分别维护加密序号集合，仅在服务端生效。
 * </p>
 */
public final class CurrentLinksPrivacySavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_current_links_privacy";
	private static final String KEY_MASKED_TRIGGER_SOURCE_SERIALS = "maskedTriggerSourceSerials";
	private static final String KEY_MASKED_CORE_SERIALS = "maskedCoreSerials";

	private static final SavedData.Factory<CurrentLinksPrivacySavedData> FACTORY = new SavedData.Factory<>(
		CurrentLinksPrivacySavedData::new,
		CurrentLinksPrivacySavedData::load,
		DataFixTypes.LEVEL
	);

	private final Set<Long> maskedTriggerSourceSerials = new HashSet<>();
	private final Set<Long> maskedCoreSerials = new HashSet<>();

	/**
	 * 获取共享保密名单实例（主世界持久化）。
	 */
	public static CurrentLinksPrivacySavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static CurrentLinksPrivacySavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		CurrentLinksPrivacySavedData data = new CurrentLinksPrivacySavedData();
		readSerialSet(tag, KEY_MASKED_TRIGGER_SOURCE_SERIALS, data.maskedTriggerSourceSerials);
		readSerialSet(tag, KEY_MASKED_CORE_SERIALS, data.maskedCoreSerials);
		return data;
	}

	/**
	 * 判断指定类型+序号是否处于加密名单。
	 */
	public boolean contains(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return false;
		}
		return bucket(type).contains(serial);
	}

	/**
	 * 添加加密名单条目。
	 *
	 * @return true 表示新增成功
	 */
	public boolean add(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return false;
		}
		boolean changed = bucket(type).add(serial);
		if (changed) {
			setDirty();
		}
		return changed;
	}

	/**
	 * 移除加密名单条目。
	 *
	 * @return true 表示命中并移除
	 */
	public boolean remove(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return false;
		}
		boolean changed = bucket(type).remove(serial);
		if (changed) {
			setDirty();
		}
		return changed;
	}

	/**
	 * 批量覆盖指定类型的加密名单。
	 *
	 * @param type 节点类型
	 * @param serials 新序号集合
	 * @return 发生变化的条目数量（新增+移除）
	 */
	public int replace(LinkNodeType type, Set<Long> serials) {
		if (type == null) {
			return 0;
		}
		Set<Long> targetBucket = bucket(type);
		Set<Long> normalized = normalizeSerialSet(serials);

		int removed = 0;
		for (long existing : targetBucket) {
			if (!normalized.contains(existing)) {
				removed++;
			}
		}
		int added = 0;
		for (long value : normalized) {
			if (!targetBucket.contains(value)) {
				added++;
			}
		}
		int changed = removed + added;
		if (changed == 0) {
			return 0;
		}

		targetBucket.clear();
		targetBucket.addAll(normalized);
		setDirty();
		return changed;
	}

	/**
	 * 列出指定类型的加密名单序号。
	 */
	public Set<Long> list(LinkNodeType type) {
		if (type == null) {
			return Collections.emptySet();
		}
		Set<Long> serials = bucket(type);
		if (serials.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(serials);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		tag.putLongArray(KEY_MASKED_TRIGGER_SOURCE_SERIALS, toSortedLongArray(maskedTriggerSourceSerials));
		tag.putLongArray(KEY_MASKED_CORE_SERIALS, toSortedLongArray(maskedCoreSerials));
		return tag;
	}

	/**
	 * 按节点类型获取对应加密集合。
	 */
	private Set<Long> bucket(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? maskedTriggerSourceSerials : maskedCoreSerials;
	}

	/**
	 * 规范化输入序号集合：仅保留正整数并去重。
	 */
	private static Set<Long> normalizeSerialSet(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalized = new HashSet<>();
		for (Long value : serials) {
			if (value != null && value > 0L) {
				normalized.add(value);
			}
		}
		return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
	}

	/**
	 * 从 NBT long array 读取序号集合。
	 */
	private static void readSerialSet(CompoundTag tag, String key, Set<Long> output) {
		if (!tag.contains(key, Tag.TAG_LONG_ARRAY)) {
			return;
		}
		for (long serial : tag.getLongArray(key)) {
			if (serial > 0L) {
				output.add(serial);
			}
		}
	}

	/**
	 * 序号集合转升序 long[]，便于稳定持久化。
	 */
	private static long[] toSortedLongArray(Set<Long> serials) {
		return serials.stream()
			.filter(value -> value != null && value > 0L)
			.mapToLong(Long::longValue)
			.sorted()
			.toArray();
	}
}
