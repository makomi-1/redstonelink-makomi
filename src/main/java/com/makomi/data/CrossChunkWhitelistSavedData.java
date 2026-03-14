package com.makomi.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 跨区块强制加载白名单 SavedData。
 * <p>
 * 按“来源/目标 + 类型 + 序号”存储运行态白名单。
 * </p>
 */
public final class CrossChunkWhitelistSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_crosschunk_whitelist";
	private static final String KEY_SOURCES = "sources";
	private static final String KEY_TARGETS = "targets";
	private static final String KEY_TYPE = "type";
	private static final String KEY_SERIALS = "serials";
	private static final String KEY_SERIAL = "serial";

	private static final SavedData.Factory<CrossChunkWhitelistSavedData> FACTORY = new SavedData.Factory<>(
		CrossChunkWhitelistSavedData::new,
		CrossChunkWhitelistSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<LinkNodeType, Set<Long>> sourceWhitelist = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> targetWhitelist = new HashMap<>();

	/**
	 * 获取跨区块白名单数据实例。
	 */
	public static CrossChunkWhitelistSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static CrossChunkWhitelistSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		CrossChunkWhitelistSavedData data = new CrossChunkWhitelistSavedData();
		data.readBucket(tag, KEY_SOURCES, data.sourceWhitelist);
		data.readBucket(tag, KEY_TARGETS, data.targetWhitelist);
		return data;
	}

	/**
	 * 添加白名单条目。
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @return 是否新增成功
	 */
	public boolean add(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Map<LinkNodeType, Set<Long>> bucket = bucket(role);
		Set<Long> serials = bucket.computeIfAbsent(type, key -> new HashSet<>());
		boolean changed = serials.add(serial);
		if (changed) {
			setDirty();
		}
		return changed;
	}

	/**
	 * 移除白名单条目。
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @return 是否移除成功
	 */
	public boolean remove(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Map<LinkNodeType, Set<Long>> bucket = bucket(role);
		Set<Long> serials = bucket.get(type);
		if (serials == null || !serials.remove(serial)) {
			return false;
		}
		if (serials.isEmpty()) {
			bucket.remove(type);
		}
		setDirty();
		return true;
	}

	/**
	 * 判断是否在白名单中。
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @return 是否命中
	 */
	public boolean contains(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Set<Long> serials = bucket(role).get(type);
		return serials != null && serials.contains(serial);
	}

	/**
	 * 列出指定类型的白名单序列号集合。
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @return 白名单序列号集合
	 */
	public Set<Long> list(LinkNodeType type, LinkNodeSemantics.Role role) {
		if (type == null || role == null) {
			return Collections.emptySet();
		}
		Set<Long> serials = bucket(role).get(type);
		if (serials == null || serials.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(serials);
	}

	/**
	 * 清空指定类型的白名单。
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @return 移除数量
	 */
	public int clear(LinkNodeType type, LinkNodeSemantics.Role role) {
		if (type == null || role == null) {
			return 0;
		}
		Set<Long> serials = bucket(role).remove(type);
		if (serials == null || serials.isEmpty()) {
			return 0;
		}
		int removed = serials.size();
		setDirty();
		return removed;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		writeBucket(tag, KEY_SOURCES, sourceWhitelist);
		writeBucket(tag, KEY_TARGETS, targetWhitelist);
		return tag;
	}

	private Map<LinkNodeType, Set<Long>> bucket(LinkNodeSemantics.Role role) {
		return role == LinkNodeSemantics.Role.SOURCE ? sourceWhitelist : targetWhitelist;
	}

	private void readBucket(CompoundTag root, String key, Map<LinkNodeType, Set<Long>> target) {
		target.clear();
		ListTag listTag = root.getList(key, Tag.TAG_COMPOUND);
		for (Tag entry : listTag) {
			CompoundTag entryTag = (CompoundTag) entry;
			Optional<LinkNodeType> type = LinkNodeSemantics.tryParseCanonicalType(entryTag.getString(KEY_TYPE));
			if (type.isEmpty()) {
				continue;
			}
			Set<Long> serials = new HashSet<>();
			ListTag serialList = entryTag.getList(KEY_SERIALS, Tag.TAG_LONG);
			for (Tag serialTag : serialList) {
				if (!(serialTag instanceof LongTag longTag)) {
					continue;
				}
				long serial = longTag.getAsLong();
				if (serial > 0L) {
					serials.add(serial);
				}
			}
			if (!serials.isEmpty()) {
				target.put(type.get(), serials);
			}
		}
	}

	private void writeBucket(CompoundTag root, String key, Map<LinkNodeType, Set<Long>> source) {
		ListTag listTag = new ListTag();
		for (Map.Entry<LinkNodeType, Set<Long>> entry : source.entrySet()) {
			Set<Long> serials = entry.getValue();
			if (serials == null || serials.isEmpty()) {
				continue;
			}
			CompoundTag entryTag = new CompoundTag();
			entryTag.putString(KEY_TYPE, LinkNodeSemantics.toSemanticName(entry.getKey()));
			ListTag serialList = new ListTag();
			for (Long serial : serials) {
				if (serial == null || serial <= 0L) {
					continue;
				}
				serialList.add(LongTag.valueOf(serial));
			}
			entryTag.put(KEY_SERIALS, serialList);
			listTag.add(entryTag);
		}
		root.put(key, listTag);
	}
}
