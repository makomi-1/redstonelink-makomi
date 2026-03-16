package com.makomi.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * RedstoneLink 世界级持久化数据。
 * <p>
 * 负责维护节点序列号分配、在线节点快照、退役集合与按钮-核心关联关系。
 * 数据统一存储在主世界数据存储中，供跨维度联动查询与写回使用。
 * </p>
 */
public final class LinkSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_serial_data";
	private static final String KEY_NEXT_SERIAL = "nextSerial";
	private static final String KEY_NEXT_CORE_SERIAL = "nextCoreSerial";
	private static final String KEY_NEXT_BUTTON_SERIAL = "nextButtonSerial";
	private static final String KEY_NODES = "nodes";
	private static final String KEY_SERIAL = "serial";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_POS = "pos";
	private static final String KEY_TYPE = "type";
	private static final String KEY_LINKS = "links";
	private static final String KEY_SOURCE_SERIAL = "sourceSerial";
	private static final String KEY_TARGET_SERIALS = "targetSerials";
	private static final String KEY_ALLOCATED_CORE_SERIALS = "allocatedCoreSerials";
	private static final String KEY_ALLOCATED_BUTTON_SERIALS = "allocatedButtonSerials";
	private static final String KEY_RETIRED_CORE_SERIALS = "retiredCoreSerials";
	private static final String KEY_RETIRED_BUTTON_SERIALS = "retiredButtonSerials";

	private static final SavedData.Factory<LinkSavedData> FACTORY = new SavedData.Factory<>(
		LinkSavedData::new,
		LinkSavedData::load,
		DataFixTypes.LEVEL
	);

	private long nextCoreSerial = 1L;
	private long nextButtonSerial = 1L;
	private final Map<Long, LinkNode> coreNodes = new HashMap<>();
	private final Map<Long, LinkNode> buttonNodes = new HashMap<>();
	private final Map<Long, Set<Long>> buttonToCores = new HashMap<>();
	private final Map<Long, Set<Long>> coreToButtons = new HashMap<>();
	private final Set<Long> allocatedCoreSerials = new HashSet<>();
	private final Set<Long> allocatedButtonSerials = new HashSet<>();
	private final Set<Long> retiredCoreSerials = new HashSet<>();
	private final Set<Long> retiredButtonSerials = new HashSet<>();

	/**
	 * 获取当前服务器共享的联动存档数据实例。
	 */
	public static LinkSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static LinkSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		LinkSavedData data = new LinkSavedData();

		if (tag.contains(KEY_NEXT_CORE_SERIAL, Tag.TAG_LONG)) {
			data.nextCoreSerial = Math.max(1L, tag.getLong(KEY_NEXT_CORE_SERIAL));
		} else if (tag.contains(KEY_NEXT_SERIAL, Tag.TAG_LONG)) {
			long legacyNext = Math.max(1L, tag.getLong(KEY_NEXT_SERIAL));
			data.nextCoreSerial = legacyNext;
			data.nextButtonSerial = legacyNext;
		}

		if (tag.contains(KEY_NEXT_BUTTON_SERIAL, Tag.TAG_LONG)) {
			data.nextButtonSerial = Math.max(1L, tag.getLong(KEY_NEXT_BUTTON_SERIAL));
		}

		ListTag nodesTag = tag.getList(KEY_NODES, Tag.TAG_COMPOUND);
		for (Tag entryTag : nodesTag) {
			CompoundTag compound = (CompoundTag) entryTag;
			long serial = compound.getLong(KEY_SERIAL);
			if (serial <= 0L) {
				continue;
			}

			ResourceLocation dimensionId = ResourceLocation.tryParse(compound.getString(KEY_DIMENSION));
			if (dimensionId == null) {
				continue;
			}

			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
			BlockPos pos = BlockPos.of(compound.getLong(KEY_POS));
			Optional<LinkNodeType> parsedType = parseStoredNodeType(compound);
			if (parsedType.isEmpty()) {
				continue;
			}
			LinkNodeType type = parsedType.get();
			data.nodeMap(type).put(serial, new LinkNode(serial, dimension, pos, type));
		}

		ListTag linksTag = tag.getList(KEY_LINKS, Tag.TAG_COMPOUND);
		for (Tag entryTag : linksTag) {
			CompoundTag compound = (CompoundTag) entryTag;
			long sourceSerial = compound.getLong(KEY_SOURCE_SERIAL);
			if (sourceSerial <= 0L) {
				continue;
			}

			for (long targetSerial : compound.getLongArray(KEY_TARGET_SERIALS)) {
				if (targetSerial <= 0L) {
					continue;
				}
				data.link(sourceSerial, targetSerial);
			}
		}

		boolean hasAllocatedCore = tag.contains(KEY_ALLOCATED_CORE_SERIALS, Tag.TAG_LONG_ARRAY);
		boolean hasAllocatedButton = tag.contains(KEY_ALLOCATED_BUTTON_SERIALS, Tag.TAG_LONG_ARRAY);
		if (hasAllocatedCore) {
			readSerialSet(tag, KEY_ALLOCATED_CORE_SERIALS, data.allocatedCoreSerials);
		}
		if (hasAllocatedButton) {
			readSerialSet(tag, KEY_ALLOCATED_BUTTON_SERIALS, data.allocatedButtonSerials);
		}
		if (!hasAllocatedCore) {
			populateLegacyAllocated(data.allocatedCoreSerials, data.nextCoreSerial);
		}
		if (!hasAllocatedButton) {
			populateLegacyAllocated(data.allocatedButtonSerials, data.nextButtonSerial);
		}
		readSerialSet(tag, KEY_RETIRED_CORE_SERIALS, data.retiredCoreSerials);
		readSerialSet(tag, KEY_RETIRED_BUTTON_SERIALS, data.retiredButtonSerials);
		data.ensureKnownSerialsAllocated();
		data.correctNextSerials();
		return data;
	}

	/**
	 * 解析存档节点类型文本。
	 * <p>
	 * 当前采用 strict 语义解析（仅 triggerSource/core）；
	 * 解析失败的节点在 load 阶段直接过滤。
	 * </p>
	 *
	 * @param compound 节点条目 NBT
	 * @return 解析后的类型；非法时返回 empty
	 */
	private static Optional<LinkNodeType> parseStoredNodeType(CompoundTag compound) {
		if (compound == null) {
			return Optional.empty();
		}
		return LinkNodeSemantics.tryParseCanonicalType(compound.getString(KEY_TYPE));
	}

	/**
	 * 为指定节点类型分配新序列号并立即标记为已分配。
	 */
	public long allocateSerial(LinkNodeType type) {
		long serial = allocateFromCounter(type);
		markAllocatedInternal(type, serial);
		setDirty();
		return serial;
	}

	/**
	 * 解析放置场景下最终可用的序列号。
	 * <p>
	 * 该过程会处理退役冲突、未登记序列号补登记以及“同序列号不同坐标”冲突重分配。
	 * </p>
	 */
	public long resolvePlacementSerial(LinkNodeType type, long preferredSerial, ResourceKey<Level> dimension, BlockPos pos) {
		long serial = preferredSerial;
		boolean changed = false;

		if (serial <= 0L || isSerialRetired(type, serial)) {
			serial = allocateFromCounter(type);
			markAllocatedInternal(type, serial);
			changed = true;
		} else if (!isSerialAllocated(type, serial)) {
			changed = markAllocatedInternal(type, serial);
		}

		LinkNode existing = nodeMap(type).get(serial);
		if (existing != null && (!existing.dimension().equals(dimension) || !existing.pos().equals(pos))) {
			serial = allocateFromCounter(type);
			markAllocatedInternal(type, serial);
			changed = true;
		}

		if (changed) {
			setDirty();
		}
		return serial;
	}

	/**
	 * 注册（或更新）在线节点坐标信息。
	 */
	public void registerNode(long serial, ResourceKey<Level> dimension, BlockPos pos, LinkNodeType type) {
		if (serial <= 0L) {
			return;
		}

		LinkNode node = new LinkNode(serial, dimension, pos.immutable(), type);
		LinkNode previous = nodeMap(type).put(serial, node);
		boolean changed = previous == null || !previous.equals(node);
		if (markAllocatedInternal(type, serial) || changed) {
			setDirty();
		}
	}

	/**
	 * 从在线节点表中移除指定节点。
	 */
	public void removeNode(LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return;
		}

		if (nodeMap(type).remove(serial) != null) {
			setDirty();
		}
	}

	/**
	 * 退役节点并清理其关联关系。
	 *
	 * @return 退役结果，包含节点是否移除、清理链接数与是否新增退役标记
	 */
	public RetireResult retireNode(LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return new RetireResult(false, 0, false);
		}

		boolean allocatedMarked = markAllocatedInternal(type, serial);
		boolean retiredMarked = markRetiredInternal(type, serial);
		boolean removed = nodeMap(type).remove(serial) != null;
		int clearedLinks = clearLinksForNode(type, serial);
		if (allocatedMarked || retiredMarked || removed || clearedLinks > 0) {
			setDirty();
		}
		return new RetireResult(removed, clearedLinks, retiredMarked);
	}

	/**
	 * 查询节点快照。
	 */
	public Optional<LinkNode> findNode(LinkNodeType type, long serial) {
		return Optional.ofNullable(nodeMap(type).get(serial));
	}

	/**
	 * 判断序列号是否已登记分配。
	 */
	public boolean isSerialAllocated(LinkNodeType type, long serial) {
		return serial > 0L && allocatedSerialSet(type).contains(serial);
	}

	/**
	 * 判断序列号是否已退役。
	 */
	public boolean isSerialRetired(LinkNodeType type, long serial) {
		return serial > 0L && retiredSerialSet(type).contains(serial);
	}

	/**
	 * 判断序列号是否处于可用激活状态（已分配且未退役）。
	 */
	public boolean isSerialActive(LinkNodeType type, long serial) {
		return isSerialAllocated(type, serial) && !isSerialRetired(type, serial);
	}

	/**
	 * 手动登记序列号为“已分配”。
	 */
	public boolean markSerialAllocated(LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return false;
		}
		boolean changed = markAllocatedInternal(type, serial);
		if (changed) {
			setDirty();
		}
		return changed;
	}

	/**
	 * 获取指定节点类型的活跃序列号集合。
	 */
	public Set<Long> getActiveSerials(LinkNodeType type) {
		Set<Long> active = new HashSet<>(allocatedSerialSet(type));
		active.removeAll(retiredSerialSet(type));
		return Set.copyOf(active);
	}

	/**
	 * 获取指定节点类型的退役序列号集合。
	 */
	public Set<Long> getRetiredSerials(LinkNodeType type) {
		return Set.copyOf(retiredSerialSet(type));
	}

	/**
	 * 获取指定节点类型的在线序列号集合。
	 */
	public Set<Long> getOnlineSerials(LinkNodeType type) {
		return Set.copyOf(nodeMap(type).keySet());
	}

	/**
	 * 切换按钮与核心之间的关联关系。
	 *
	 * @return true 表示本次切换后为“已关联”，false 表示切换后为“未关联”
	 */
	public boolean toggleLink(long buttonSerial, long coreSerial) {
		if (buttonSerial <= 0L || coreSerial <= 0L) {
			return false;
		}

		Set<Long> linkedCores = buttonToCores.computeIfAbsent(buttonSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			unlink(buttonSerial, coreSerial);
			setDirty();
			return false;
		}

		link(buttonSerial, coreSerial);
		setDirty();
		return true;
	}

	/**
	 * 以“来源/目标”语义切换关联关系。
	 * <p>
	 * 当来源类型为 TRIGGER_SOURCE 时，按 TRIGGER_SOURCE -> CORE 写入；
	 * 当来源类型为 CORE 时，自动映射为 TRIGGER_SOURCE -> CORE 底层索引。
	 * </p>
	 *
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 * @param targetSerial 目标序列号
	 * @return true 表示本次切换后为“已关联”，false 表示切换后为“未关联”
	 */
	public boolean toggleLinkBySourceType(LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		if (sourceType == null) {
			return false;
		}
		return sourceType == LinkNodeType.TRIGGER_SOURCE
			? toggleLink(sourceSerial, targetSerial)
			: toggleLink(targetSerial, sourceSerial);
	}

	/**
	 * 解除按钮与核心的单条关联关系。
	 */
	public boolean unlink(long buttonSerial, long coreSerial) {
		Set<Long> linkedCores = buttonToCores.get(buttonSerial);
		if (linkedCores == null || !linkedCores.remove(coreSerial)) {
			return false;
		}

		if (linkedCores.isEmpty()) {
			buttonToCores.remove(buttonSerial);
		}

		Set<Long> linkedButtons = coreToButtons.get(coreSerial);
		if (linkedButtons != null) {
			linkedButtons.remove(buttonSerial);
			if (linkedButtons.isEmpty()) {
				coreToButtons.remove(coreSerial);
			}
		}
		return true;
	}

	/**
	 * 查询按钮关联的核心序列号集合。
	 */
	public Set<Long> getLinkedCores(long buttonSerial) {
		Set<Long> linked = buttonToCores.get(buttonSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 查询核心被哪些按钮关联。
	 */
	public Set<Long> getLinkedButtons(long coreSerial) {
		Set<Long> linked = coreToButtons.get(coreSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 按“来源类型 + 来源序列号”查询目标集合。
	 * <p>
	 * 当来源类型为 TRIGGER_SOURCE 时，返回关联 CORE 集合；
	 * 当来源类型为 CORE 时，返回关联 TRIGGER_SOURCE 集合。
	 * </p>
	 *
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 * @return 目标序列号集合
	 */
	public Set<Long> getLinkedTargetsBySourceType(LinkNodeType sourceType, long sourceSerial) {
		if (sourceType == null) {
			return Collections.emptySet();
		}
		return sourceType == LinkNodeType.TRIGGER_SOURCE
			? getLinkedCores(sourceSerial)
			: getLinkedButtons(sourceSerial);
	}

	/**
	 * 清理指定节点的全部关联关系。
	 *
	 * @return 被移除的链接数量
	 */
	public int clearLinksForNode(LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return 0;
		}

		int removed = 0;
		if (type == LinkNodeType.TRIGGER_SOURCE) {
			Set<Long> cores = buttonToCores.remove(serial);
			if (cores == null || cores.isEmpty()) {
				return 0;
			}

			for (long coreSerial : cores) {
				Set<Long> buttons = coreToButtons.get(coreSerial);
				if (buttons != null) {
					buttons.remove(serial);
					if (buttons.isEmpty()) {
						coreToButtons.remove(coreSerial);
					}
				}
				removed++;
			}
		} else {
			Set<Long> buttons = coreToButtons.remove(serial);
			if (buttons == null || buttons.isEmpty()) {
				return 0;
			}

			for (long buttonSerial : buttons) {
				Set<Long> cores = buttonToCores.get(buttonSerial);
				if (cores != null) {
					cores.remove(serial);
					if (cores.isEmpty()) {
						buttonToCores.remove(buttonSerial);
					}
				}
				removed++;
			}
		}

		if (removed > 0) {
			setDirty();
		}
		return removed;
	}

	/**
	 * 生成当前链路拓扑审计快照。
	 */
	public AuditSnapshot createAuditSnapshot() {
		int linkCount = 0;
		int linksWithMissingEndpoint = 0;

		for (Map.Entry<Long, Set<Long>> entry : buttonToCores.entrySet()) {
			boolean buttonOnline = buttonNodes.containsKey(entry.getKey());
			for (long coreSerial : entry.getValue()) {
				linkCount++;
				boolean coreOnline = coreNodes.containsKey(coreSerial);
				if (!buttonOnline || !coreOnline) {
					linksWithMissingEndpoint++;
				}
			}
		}

		return new AuditSnapshot(
			coreNodes.size(),
			buttonNodes.size(),
			linkCount,
			linksWithMissingEndpoint,
			buttonToCores.size(),
			coreToButtons.size()
		);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		tag.putLong(KEY_NEXT_CORE_SERIAL, nextCoreSerial);
		tag.putLong(KEY_NEXT_BUTTON_SERIAL, nextButtonSerial);
		tag.putLongArray(KEY_ALLOCATED_CORE_SERIALS, sortedSerialArray(allocatedCoreSerials));
		tag.putLongArray(KEY_ALLOCATED_BUTTON_SERIALS, sortedSerialArray(allocatedButtonSerials));
		tag.putLongArray(KEY_RETIRED_CORE_SERIALS, sortedSerialArray(retiredCoreSerials));
		tag.putLongArray(KEY_RETIRED_BUTTON_SERIALS, sortedSerialArray(retiredButtonSerials));

		ListTag nodesTag = new ListTag();
		saveNodeMap(nodesTag, coreNodes);
		saveNodeMap(nodesTag, buttonNodes);
		tag.put(KEY_NODES, nodesTag);

		ListTag linksTag = new ListTag();
		for (Map.Entry<Long, Set<Long>> entry : buttonToCores.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			CompoundTag compound = new CompoundTag();
			compound.putLong(KEY_SOURCE_SERIAL, entry.getKey());
			compound.putLongArray(KEY_TARGET_SERIALS, entry.getValue().stream().toList());
			linksTag.add(compound);
		}
		tag.put(KEY_LINKS, linksTag);
		return tag;
	}

	private void saveNodeMap(ListTag nodesTag, Map<Long, LinkNode> map) {
		for (LinkNode node : map.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putLong(KEY_SERIAL, node.serial());
			entry.putString(KEY_DIMENSION, node.dimension().location().toString());
			entry.putLong(KEY_POS, node.pos().asLong());
			entry.putString(KEY_TYPE, LinkNodeSemantics.toSemanticName(node.type()));
			nodesTag.add(entry);
		}
	}

	/**
	 * 建立按钮与核心的双向索引关系。
	 */
	private void link(long buttonSerial, long coreSerial) {
		buttonToCores.computeIfAbsent(buttonSerial, unused -> new HashSet<>()).add(coreSerial);
		coreToButtons.computeIfAbsent(coreSerial, unused -> new HashSet<>()).add(buttonSerial);
	}

	/**
	 * 修正下一可分配序列号，确保始终大于当前已知最大序列号。
	 */
	private void correctNextSerials() {
		long maxButtonSerial = 0L;
		long maxCoreSerial = 0L;

		for (long serial : buttonNodes.keySet()) {
			maxButtonSerial = Math.max(maxButtonSerial, serial);
		}
		for (long serial : coreNodes.keySet()) {
			maxCoreSerial = Math.max(maxCoreSerial, serial);
		}
		for (Map.Entry<Long, Set<Long>> entry : buttonToCores.entrySet()) {
			maxButtonSerial = Math.max(maxButtonSerial, entry.getKey());
			for (long coreSerial : entry.getValue()) {
				maxCoreSerial = Math.max(maxCoreSerial, coreSerial);
			}
		}
		maxButtonSerial = Math.max(maxButtonSerial, maxValue(allocatedButtonSerials));
		maxButtonSerial = Math.max(maxButtonSerial, maxValue(retiredButtonSerials));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(allocatedCoreSerials));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(retiredCoreSerials));

		nextButtonSerial = Math.max(nextButtonSerial, maxButtonSerial + 1L);
		nextCoreSerial = Math.max(nextCoreSerial, maxCoreSerial + 1L);
	}

	/**
	 * 从计数器分配序列号，并跳过在线/已分配/已退役序列号。
	 */
	private long allocateFromCounter(LinkNodeType type) {
		Map<Long, LinkNode> onlineNodes = nodeMap(type);
		Set<Long> allocatedSerials = allocatedSerialSet(type);
		Set<Long> retiredSerials = retiredSerialSet(type);
		if (type == LinkNodeType.TRIGGER_SOURCE) {
			while (
				onlineNodes.containsKey(nextButtonSerial)
					|| allocatedSerials.contains(nextButtonSerial)
					|| retiredSerials.contains(nextButtonSerial)
			) {
				nextButtonSerial++;
			}
			long serial = nextButtonSerial;
			nextButtonSerial++;
			return serial;
		}

		while (
			onlineNodes.containsKey(nextCoreSerial)
				|| allocatedSerials.contains(nextCoreSerial)
				|| retiredSerials.contains(nextCoreSerial)
		) {
			nextCoreSerial++;
		}
		long serial = nextCoreSerial;
		nextCoreSerial++;
		return serial;
	}

	/**
	 * 按类型获取在线节点表。
	 */
	private Map<Long, LinkNode> nodeMap(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? buttonNodes : coreNodes;
	}

	/**
	 * 按类型获取已分配序列号集合。
	 */
	private Set<Long> allocatedSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? allocatedButtonSerials : allocatedCoreSerials;
	}

	/**
	 * 按类型获取退役序列号集合。
	 */
	private Set<Long> retiredSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? retiredButtonSerials : retiredCoreSerials;
	}

	private boolean markAllocatedInternal(LinkNodeType type, long serial) {
		return allocatedSerialSet(type).add(serial);
	}

	private boolean markRetiredInternal(LinkNodeType type, long serial) {
		return retiredSerialSet(type).add(serial);
	}

	/**
	 * 将已知节点与链接中出现过的序列号补登记到“已分配集合”。
	 */
	private void ensureKnownSerialsAllocated() {
		for (long serial : coreNodes.keySet()) {
			allocatedCoreSerials.add(serial);
		}
		for (long serial : buttonNodes.keySet()) {
			allocatedButtonSerials.add(serial);
		}
		for (Map.Entry<Long, Set<Long>> entry : buttonToCores.entrySet()) {
			allocatedButtonSerials.add(entry.getKey());
			for (long coreSerial : entry.getValue()) {
				allocatedCoreSerials.add(coreSerial);
			}
		}
	}

	/**
	 * 从 NBT long array 读取序列号集合。
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
	 * 兼容旧版：根据 nextSerial 推导历史已分配范围。
	 */
	private static void populateLegacyAllocated(Set<Long> output, long nextSerial) {
		long upperExclusive = Math.max(1L, nextSerial);
		for (long serial = 1L; serial < upperExclusive; serial++) {
			output.add(serial);
		}
	}

	/**
	 * 将序列号集合转为升序 long[]，用于稳定持久化。
	 */
	private static long[] sortedSerialArray(Set<Long> serials) {
		return serials.stream()
			.filter(value -> value != null && value > 0L)
			.mapToLong(Long::longValue)
			.sorted()
			.toArray();
	}

	/**
	 * 求集合中的最大值；空集合返回 0。
	 */
	private static long maxValue(Set<Long> values) {
		long max = 0L;
		for (long value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	/**
	 * 在线节点快照记录。
	 */
	public record LinkNode(long serial, ResourceKey<Level> dimension, BlockPos pos, LinkNodeType type) {}

	/**
	 * 节点退役结果记录。
	 */
	public record RetireResult(boolean nodeRemoved, int linksRemoved, boolean retiredMarked) {}

	/**
	 * 联动图谱审计快照记录。
	 */
	public record AuditSnapshot(
		int onlineCoreNodes,
		int onlineButtonNodes,
		int totalLinks,
		int linksWithMissingEndpoint,
		int linkedButtonSerialCount,
		int linkedCoreSerialCount
	) {}
}
