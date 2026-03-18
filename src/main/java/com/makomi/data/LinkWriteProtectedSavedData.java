package com.makomi.data;

import com.makomi.util.IncrementalReplacePlanUtil;
import com.makomi.util.SerialNbtCodecUtil;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 链接写入受控名单持久化数据。
 * <p>
 * 按 triggerSource/core 分别维护受控序号集合，命中后需满足配置权限才允许写入。
 * </p>
 */
public final class LinkWriteProtectedSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_link_write_protected";
	private static final String KEY_PROTECTED_TRIGGER_SOURCE_SERIALS = "protectedTriggerSourceSerials";
	private static final String KEY_PROTECTED_CORE_SERIALS = "protectedCoreSerials";

	private static final SavedData.Factory<LinkWriteProtectedSavedData> FACTORY = new SavedData.Factory<>(
		LinkWriteProtectedSavedData::new,
		LinkWriteProtectedSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Set<Long> protectedTriggerSourceSerials = new HashSet<>();
	private final Set<Long> protectedCoreSerials = new HashSet<>();

	/**
	 * 获取共享受控名单实例（主世界持久化）。
	 */
	public static LinkWriteProtectedSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static LinkWriteProtectedSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		LinkWriteProtectedSavedData data = new LinkWriteProtectedSavedData();
		SerialNbtCodecUtil.readSerialSet(tag, KEY_PROTECTED_TRIGGER_SOURCE_SERIALS, data.protectedTriggerSourceSerials);
		SerialNbtCodecUtil.readSerialSet(tag, KEY_PROTECTED_CORE_SERIALS, data.protectedCoreSerials);
		return data;
	}

	/**
	 * 判断指定类型+序号是否命中受控名单。
	 */
	public boolean contains(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return false;
		}
		return bucket(type).contains(serial);
	}

	/**
	 * 添加受控名单条目。
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
	 * 移除受控名单条目。
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
	 * 覆盖指定类型受控名单，并按增量差异统计结果。
	 */
	public ReplaceProtectedResult replace(LinkNodeType type, Set<Long> serials) {
		if (type == null) {
			return new ReplaceProtectedResult(0, 0, 0);
		}
		Set<Long> targetBucket = bucket(type);
		Set<Long> normalized = normalizeSerialSet(serials);
		IncrementalReplacePlanUtil.SetReplacePlan<Long> plan = IncrementalReplacePlanUtil.buildSetReplacePlan(
			targetBucket,
			normalized
		);
		if (!plan.changed()) {
			return new ReplaceProtectedResult(0, 0, 0);
		}
		targetBucket.removeAll(plan.toRemove());
		targetBucket.addAll(plan.toAdd());
		setDirty();
		return new ReplaceProtectedResult(plan.addedCount(), plan.removedCount(), plan.changedCount());
	}

	/**
	 * 列出指定类型受控名单。
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
		tag.putLongArray(KEY_PROTECTED_TRIGGER_SOURCE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(protectedTriggerSourceSerials));
		tag.putLongArray(KEY_PROTECTED_CORE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(protectedCoreSerials));
		return tag;
	}

	/**
	 * 按节点类型获取对应受控集合。
	 */
	private Set<Long> bucket(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? protectedTriggerSourceSerials : protectedCoreSerials;
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
	 * protected set 覆盖结果记录。
	 */
	public record ReplaceProtectedResult(int addedCount, int removedCount, int changedCount) {}
}
