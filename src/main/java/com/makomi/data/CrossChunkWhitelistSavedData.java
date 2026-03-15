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
	private static final String KEY_RESIDENT_SERIALS = "residentSerials";

	private static final SavedData.Factory<CrossChunkWhitelistSavedData> FACTORY = new SavedData.Factory<>(
		CrossChunkWhitelistSavedData::new,
		CrossChunkWhitelistSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<LinkNodeType, Set<Long>> sourceWhitelist = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> targetWhitelist = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> sourceResidents = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> targetResidents = new HashMap<>();

	/**
	 * 获取跨区块白名单数据实例。
	 */
	public static CrossChunkWhitelistSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static CrossChunkWhitelistSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		CrossChunkWhitelistSavedData data = new CrossChunkWhitelistSavedData();
		data.readBucket(tag, KEY_SOURCES, data.sourceWhitelist, data.sourceResidents);
		data.readBucket(tag, KEY_TARGETS, data.targetWhitelist, data.targetResidents);
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
		return upsert(type, serial, role, false).changed();
	}

	/**
	 * 添加（或覆盖）白名单条目，并按命令语义设置常驻标记。
	 * <p>
	 * 约束：resident 严格依赖 whitelist，始终保持 resident ⊆ whitelist。
	 * </p>
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @param resident 是否常驻
	 * @return 是否发生状态变化
	 */
	public boolean add(LinkNodeType type, long serial, LinkNodeSemantics.Role role, boolean resident) {
		return upsert(type, serial, role, resident).changed();
	}

	/**
	 * 执行白名单条目写入，并返回变更细节。
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @param resident 是否常驻
	 * @return 写入变更结果
	 */
	public UpsertResult upsert(LinkNodeType type, long serial, LinkNodeSemantics.Role role, boolean resident) {
		if (type == null || serial <= 0L || role == null) {
			return UpsertResult.invalid();
		}
		Map<LinkNodeType, Set<Long>> whitelistBucket = bucket(role);
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		Set<Long> whitelistSerials = whitelistBucket.computeIfAbsent(type, key -> new HashSet<>());
		boolean created = whitelistSerials.add(serial);

		boolean residentChanged;
		if (resident) {
			Set<Long> residentSerials = residentBucket.computeIfAbsent(type, key -> new HashSet<>());
			residentChanged = residentSerials.add(serial);
		} else {
			residentChanged = removeResidentSerial(residentBucket, type, serial);
		}

		if (created || residentChanged) {
			setDirty();
		}
		return new UpsertResult(true, created, residentChanged);
	}

	/**
	 * 查询条目是否带有常驻标记。
	 *
	 * @param type 节点类型
	 * @param serial 序列号
	 * @param role 角色方向
	 * @return 是否常驻
	 */
	public boolean isResident(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Set<Long> residentSerials = residentBucket(role).get(type);
		return residentSerials != null && residentSerials.contains(serial);
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
		Map<LinkNodeType, Set<Long>> whitelistBucket = bucket(role);
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		boolean whitelistRemoved = removeWhitelistSerial(whitelistBucket, type, serial);
		boolean residentRemoved = removeResidentSerial(residentBucket, type, serial);
		if (whitelistRemoved || residentRemoved) {
			setDirty();
		}
		return whitelistRemoved;
	}

	/**
	 * 按节点类型与序号强同步清理白名单（含 resident），覆盖 SOURCE/TARGET 全角色。
	 *
	 * @param type 节点类型
	 * @param serial 节点序号
	 * @return 是否至少移除了一侧白名单条目
	 */
	public boolean removeFromAllRoles(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return false;
		}
		boolean removedFromSource = remove(type, serial, LinkNodeSemantics.Role.SOURCE);
		boolean removedFromTarget = remove(type, serial, LinkNodeSemantics.Role.TARGET);
		return removedFromSource || removedFromTarget;
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
	 * 列出指定类型的常驻白名单序列号集合。
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @return 常驻序列号集合
	 */
	public Set<Long> listResident(LinkNodeType type, LinkNodeSemantics.Role role) {
		if (type == null || role == null) {
			return Collections.emptySet();
		}
		Set<Long> serials = residentBucket(role).get(type);
		if (serials == null || serials.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(serials);
	}

	/**
	 * 返回指定角色下的常驻白名单快照。
	 *
	 * @param role 角色方向
	 * @return type -> serials 的不可变快照
	 */
	public Map<LinkNodeType, Set<Long>> residentSnapshot(LinkNodeSemantics.Role role) {
		if (role == null) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		if (residentBucket.isEmpty()) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> snapshot = new HashMap<>();
		for (Map.Entry<LinkNodeType, Set<Long>> entry : residentBucket.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			snapshot.put(entry.getKey(), Set.copyOf(entry.getValue()));
		}
		return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
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
		Map<LinkNodeType, Set<Long>> whitelistBucket = bucket(role);
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		Set<Long> serials = whitelistBucket.remove(type);
		residentBucket.remove(type);
		if (serials == null || serials.isEmpty()) {
			return 0;
		}
		int removed = serials.size();
		setDirty();
		return removed;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		writeBucket(tag, KEY_SOURCES, sourceWhitelist, sourceResidents);
		writeBucket(tag, KEY_TARGETS, targetWhitelist, targetResidents);
		return tag;
	}

	private Map<LinkNodeType, Set<Long>> bucket(LinkNodeSemantics.Role role) {
		return role == LinkNodeSemantics.Role.SOURCE ? sourceWhitelist : targetWhitelist;
	}

	private Map<LinkNodeType, Set<Long>> residentBucket(LinkNodeSemantics.Role role) {
		return role == LinkNodeSemantics.Role.SOURCE ? sourceResidents : targetResidents;
	}

	private void readBucket(
		CompoundTag root,
		String key,
		Map<LinkNodeType, Set<Long>> whitelistTarget,
		Map<LinkNodeType, Set<Long>> residentTarget
	) {
		whitelistTarget.clear();
		residentTarget.clear();
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
				whitelistTarget.put(type.get(), serials);
				Set<Long> residentSerials = new HashSet<>();
				ListTag residentSerialList = entryTag.getList(KEY_RESIDENT_SERIALS, Tag.TAG_LONG);
				for (Tag serialTag : residentSerialList) {
					if (!(serialTag instanceof LongTag longTag)) {
						continue;
					}
					long serial = longTag.getAsLong();
					if (serial > 0L && serials.contains(serial)) {
						residentSerials.add(serial);
					}
				}
				if (!residentSerials.isEmpty()) {
					residentTarget.put(type.get(), residentSerials);
				}
			}
		}
	}

	private void writeBucket(
		CompoundTag root,
		String key,
		Map<LinkNodeType, Set<Long>> whitelistSource,
		Map<LinkNodeType, Set<Long>> residentSource
	) {
		ListTag listTag = new ListTag();
		for (Map.Entry<LinkNodeType, Set<Long>> entry : whitelistSource.entrySet()) {
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
			Set<Long> residentSerials = residentSource.getOrDefault(entry.getKey(), Set.of());
			if (!residentSerials.isEmpty()) {
				ListTag residentSerialList = new ListTag();
				for (Long serial : residentSerials) {
					if (serial == null || serial <= 0L || !serials.contains(serial)) {
						continue;
					}
					residentSerialList.add(LongTag.valueOf(serial));
				}
				if (!residentSerialList.isEmpty()) {
					entryTag.put(KEY_RESIDENT_SERIALS, residentSerialList);
				}
			}
			listTag.add(entryTag);
		}
		root.put(key, listTag);
	}

	private static boolean removeWhitelistSerial(Map<LinkNodeType, Set<Long>> bucket, LinkNodeType type, long serial) {
		Set<Long> serials = bucket.get(type);
		if (serials == null || !serials.remove(serial)) {
			return false;
		}
		if (serials.isEmpty()) {
			bucket.remove(type);
		}
		return true;
	}

	private static boolean removeResidentSerial(Map<LinkNodeType, Set<Long>> residentBucket, LinkNodeType type, long serial) {
		Set<Long> residentSerials = residentBucket.get(type);
		if (residentSerials == null || !residentSerials.remove(serial)) {
			return false;
		}
		if (residentSerials.isEmpty()) {
			residentBucket.remove(type);
		}
		return true;
	}

	/**
	 * 白名单 upsert 变更结果。
	 *
	 * @param valid 输入参数是否有效
	 * @param created 是否首次写入白名单
	 * @param residentChanged 常驻标记是否变化
	 */
	public record UpsertResult(boolean valid, boolean created, boolean residentChanged) {
		private static UpsertResult invalid() {
			return new UpsertResult(false, false, false);
		}

		public boolean changed() {
			return created || residentChanged;
		}
	}
}
