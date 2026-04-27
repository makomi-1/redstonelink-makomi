package com.makomi.data;

import com.mojang.serialization.Codec;
import com.makomi.util.IncrementalReplacePlanUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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

	private static final Codec<CrossChunkWhitelistSavedData> CODEC = CompoundTag.CODEC.xmap(
		CrossChunkWhitelistSavedData::load,
		CrossChunkWhitelistSavedData::toTag
	);
	private static final SavedDataType<CrossChunkWhitelistSavedData> TYPE = new SavedDataType<>(
		DATA_NAME,
		CrossChunkWhitelistSavedData::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<LinkNodeType, Set<Long>> sourceWhitelist = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> targetWhitelist = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> sourceResidents = new HashMap<>();
	private final Map<LinkNodeType, Set<Long>> targetResidents = new HashMap<>();
	private long residentStateVersion;

	/**
	 * 获取跨区块白名单数据实例。
	 */
	public static CrossChunkWhitelistSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	private static CrossChunkWhitelistSavedData load(CompoundTag tag) {
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
	 * 获取当前 resident 白名单状态版本。
	 * <p>
	 * 仅在 resident 集合本身发生变化时递增，用于跨区块 resident 票据同步的脏检查。
	 * </p>
	 */
	public long residentStateVersion() {
		return residentStateVersion;
	}

	/**
	 * 当前是否仍存在 resident 白名单条目。
	 */
	public boolean hasResidents() {
		return !sourceResidents.isEmpty() || !targetResidents.isEmpty();
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

		if (residentChanged) {
			bumpResidentStateVersion();
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
		if (residentRemoved) {
			bumpResidentStateVersion();
		}
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
	 * 以无快照方式遍历指定角色下的 resident 序号。
	 * <p>
	 * 该入口仅用于主线程内的临时汇总路径，避免热路径每 tick 构建 resident 快照容器。
	 * </p>
	 */
	public void forEachResidentSerial(LinkNodeSemantics.Role role, ResidentSerialConsumer consumer) {
		if (role == null || consumer == null) {
			return;
		}
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		if (residentBucket.isEmpty()) {
			return;
		}
		for (Map.Entry<LinkNodeType, Set<Long>> entry : residentBucket.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			for (Long serial : entry.getValue()) {
				if (serial != null && serial > 0L) {
					consumer.accept(entry.getKey(), serial);
				}
			}
		}
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
		Set<Long> residentSerials = residentBucket.remove(type);
		boolean residentRemoved = residentSerials != null && !residentSerials.isEmpty();
		if (residentSerials != null && !residentSerials.isEmpty()) {
			bumpResidentStateVersion();
		}
		if (serials == null || serials.isEmpty()) {
			if (residentRemoved) {
				setDirty();
			}
			return 0;
		}
		int removed = serials.size();
		setDirty();
		return removed;
	}

	/**
	 * 以“覆盖集合”语义增量替换白名单。
	 * <p>
	 * 与 clear+add 语义对齐：最终 resident 状态由参数统一决定，
	 * 即最终白名单中的序号全部 resident=on 或全部 resident=off。
	 * </p>
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @param serials 新白名单集合
	 * @param resident 最终 resident 模式
	 * @return 覆盖结果（新增/移除/resident 变更/总变更）
	 */
	public ReplaceWhitelistResult replace(
		LinkNodeType type,
		LinkNodeSemantics.Role role,
		Set<Long> serials,
		boolean resident
	) {
		if (type == null || role == null) {
			return new ReplaceWhitelistResult(0, 0, 0, 0);
		}
		Map<LinkNodeType, Set<Long>> whitelistBucket = bucket(role);
		Map<LinkNodeType, Set<Long>> residentBucket = residentBucket(role);
		Set<Long> currentWhitelist = whitelistBucket.getOrDefault(type, Set.of());
		Set<Long> normalizedTargets = normalizePositiveSerialSet(serials);
		IncrementalReplacePlanUtil.SetReplacePlan<Long> plan = IncrementalReplacePlanUtil.buildSetReplacePlan(
			currentWhitelist,
			normalizedTargets
		);

		Set<Long> whitelistSerials = new HashSet<>(currentWhitelist);
		Set<Long> residentSerials = new HashSet<>(residentBucket.getOrDefault(type, Set.of()));
		int added = 0;
		int removed = 0;
		int residentChanged = 0;

		for (long serial : plan.toRemove()) {
			if (whitelistSerials.remove(serial)) {
				removed++;
			}
			if (residentSerials.remove(serial)) {
				residentChanged++;
			}
		}
		for (long serial : plan.toAdd()) {
			if (whitelistSerials.add(serial)) {
				added++;
			}
			if (resident && residentSerials.add(serial)) {
				residentChanged++;
			}
		}
		for (long serial : plan.unchanged()) {
			if (resident) {
				if (residentSerials.add(serial)) {
					residentChanged++;
				}
				continue;
			}
			if (residentSerials.remove(serial)) {
				residentChanged++;
			}
		}

		if (whitelistSerials.isEmpty()) {
			whitelistBucket.remove(type);
		} else {
			whitelistBucket.put(type, whitelistSerials);
		}
		if (residentSerials.isEmpty()) {
			residentBucket.remove(type);
		} else {
			residentBucket.put(type, residentSerials);
		}
		int changed = added + removed + residentChanged;
		if (residentChanged > 0) {
			bumpResidentStateVersion();
		}
		if (changed > 0) {
			setDirty();
		}
		return new ReplaceWhitelistResult(added, removed, residentChanged, changed);
	}

	private CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
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

	/**
	 * resident 集合发生变化时推进版本。
	 */
	private void bumpResidentStateVersion() {
		residentStateVersion++;
	}

	private void readBucket(
		CompoundTag root,
		String key,
		Map<LinkNodeType, Set<Long>> whitelistTarget,
		Map<LinkNodeType, Set<Long>> residentTarget
	) {
		whitelistTarget.clear();
		residentTarget.clear();
		ListTag listTag = root.getListOrEmpty(key);
		for (Tag entry : listTag) {
			if (!(entry instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<LinkNodeType> type = LinkNodeSemantics.tryParseCanonicalType(entryTag.getStringOr(KEY_TYPE, ""));
			if (type.isEmpty()) {
				continue;
			}
			Set<Long> serials = new HashSet<>();
			ListTag serialList = entryTag.getListOrEmpty(KEY_SERIALS);
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
				ListTag residentSerialList = entryTag.getListOrEmpty(KEY_RESIDENT_SERIALS);
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
	 * 规范化输入集合：仅保留正序号并去重。
	 */
	private static Set<Long> normalizePositiveSerialSet(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalized = new HashSet<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
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

	/**
	 * 覆盖式替换结果。
	 *
	 * @param addedCount 新增白名单数量
	 * @param removedCount 移除白名单数量
	 * @param residentChangedCount resident 状态变更数量
	 * @param changedCount 总变更数量（新增+移除+resident 变更）
	 */
	public record ReplaceWhitelistResult(
		int addedCount,
		int removedCount,
		int residentChangedCount,
		int changedCount
	) {}

	/**
	 * resident 序号遍历回调。
	 */
	@FunctionalInterface
	public interface ResidentSerialConsumer {
		void accept(LinkNodeType type, long serial);
	}
}
