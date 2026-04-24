package com.makomi.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * RedstoneLink 世界级持久化数据。
 * <p>
 * 该对象保留对外契约与字段真值，具体职责拆分为：
 * 1. 序号分配/退役 helper；
 * 2. triggerSource -> core 链接索引 helper；
 * 3. 存档编解码 helper；
 * 4. 查询/审计视图 helper。
 * </p>
 */
public final class LinkSavedData extends SavedData {
	static final String DATA_NAME = "redstonelink_serial_data";
	static final String KEY_NEXT_CORE_SERIAL = "nextCoreSerial";
	static final String KEY_NEXT_TRIGGER_SOURCE_SERIAL = "nextTriggerSourceSerial";
	static final String KEY_NODES = "nodes";
	static final String KEY_SERIAL = "serial";
	static final String KEY_DIMENSION = "dimension";
	static final String KEY_POS = "pos";
	static final String KEY_TYPE = "type";
	static final String KEY_LINKS = "links";
	static final String KEY_SOURCE_SERIAL = "sourceSerial";
	static final String KEY_TARGET_SERIALS = "targetSerials";
	static final String KEY_ALLOCATED_CORE_SERIALS = "allocatedCoreSerials";
	static final String KEY_ALLOCATED_TRIGGER_SOURCE_SERIALS = "allocatedTriggerSourceSerials";
	static final String KEY_RETIRED_CORE_SERIALS = "retiredCoreSerials";
	static final String KEY_RETIRED_TRIGGER_SOURCE_SERIALS = "retiredTriggerSourceSerials";
	static final String KEY_REPEATER_SERIALS = "repeaterSerials";
	static final String KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS = "triggerSourceReplaySyncSnapshots";
	static final String KEY_TRIGGER_SOURCE_CHANNEL_CONFIGS = "triggerSourceChannelConfigs";
	static final String KEY_CORE_CHANNEL_CONFIGS = "coreChannelConfigs";
	static final String KEY_SIGNAL_STRENGTH = "signalStrength";
	static final String KEY_TICK = "tick";
	static final String KEY_SLOT = "slot";
	static final String KEY_SEQ = "seq";
	static final String KEY_CHANNEL = "channel";

	private static final SavedData.Factory<LinkSavedData> FACTORY = new SavedData.Factory<>(
		LinkSavedData::new,
		LinkSavedData::load,
		DataFixTypes.LEVEL
	);

	long nextCoreSerial = 1L;
	long nextTriggerSourceSerial = 1L;
	final Map<Long, LinkNode> coreNodes = new HashMap<>();
	final Map<Long, LinkNode> triggerSourceNodes = new HashMap<>();
	final Map<Long, Set<Long>> triggerSourceToCores = new HashMap<>();
	final Map<Long, Set<Long>> coreToTriggerSources = new HashMap<>();
	final Set<Long> allocatedCoreSerials = new HashSet<>();
	final Set<Long> allocatedTriggerSourceSerials = new HashSet<>();
	final Set<Long> retiredCoreSerials = new HashSet<>();
	final Set<Long> retiredTriggerSourceSerials = new HashSet<>();
	final Set<Long> repeaterSerials = new HashSet<>();
	final Map<Long, Long> triggerSourceChannelConfigs = new HashMap<>();
	final Map<Long, Long> coreChannelConfigs = new HashMap<>();
	final Map<Long, Set<Long>> channelToTriggerSources = new HashMap<>();
	final Map<Long, Set<Long>> channelToCores = new HashMap<>();
	final Map<Long, ReplaySyncSnapshotRecord> triggerSourceReplaySyncSnapshots = new HashMap<>();
	long runtimeNodeVersion;
	long graphRevision;
	final Map<Long, Long> triggerSourceRevisions = new HashMap<>();
	final Map<Long, Long> coreRevisions = new HashMap<>();
	final Map<ResourceKey<Level>, Map<LinkNodeType, Map<Long, Set<Long>>>> nodeChunkIndex = new HashMap<>();

	/**
	 * 获取当前服务器共享的联动存档数据实例。
	 */
	public static LinkSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/**
	 * 保留原有反序列化入口，供反射测试与 SavedData 工厂复用。
	 */
	private static LinkSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		LinkSavedData data = LinkSavedDataCodecSupport.load(tag, provider);
		data.rebuildNodeChunkIndex();
		data.rebuildChannelIndex();
		return data;
	}

	/**
	 * 为指定节点类型分配新序列号并立即标记为已分配。
	 */
	public long allocateSerial(LinkNodeType type) {
		return LinkSavedDataSerialSupport.allocateSerial(this, type);
	}

	/**
	 * 为转发器分配一个同时绑定 `core/triggerSource` 的统一序号。
	 */
	public long allocateRepeaterSerial() {
		return LinkSavedDataSerialSupport.allocateRepeaterSerial(this);
	}

	/**
	 * 解析放置场景下最终可用的序列号。
	 */
	public long resolvePlacementSerial(LinkNodeType type, long preferredSerial, ResourceKey<Level> dimension, BlockPos pos) {
		return LinkSavedDataSerialSupport.resolvePlacementSerial(this, type, preferredSerial, dimension, pos);
	}

	/**
	 * 解析转发器放置场景下最终可用的统一序号。
	 */
	public long resolveRepeaterPlacementSerial(long preferredSerial, ResourceKey<Level> dimension, BlockPos pos) {
		return LinkSavedDataSerialSupport.resolveRepeaterPlacementSerial(this, preferredSerial, dimension, pos);
	}

	/**
	 * 注册（或更新）在线节点坐标信息。
	 */
	public void registerNode(long serial, ResourceKey<Level> dimension, BlockPos pos, LinkNodeType type) {
		LinkSavedDataSerialSupport.registerNode(this, serial, dimension, pos, type);
	}

	/**
	 * 从在线节点表中移除指定节点。
	 */
	public void removeNode(LinkNodeType type, long serial) {
		LinkSavedDataSerialSupport.removeNode(this, type, serial);
	}

	/**
	 * 退役节点并清理其关联关系。
	 */
	public RetireResult retireNode(LinkNodeType type, long serial) {
		return LinkSavedDataSerialSupport.retireNode(this, type, serial);
	}

	/**
	 * 查询节点快照。
	 */
	public Optional<LinkNode> findNode(LinkNodeType type, long serial) {
		return LinkSavedDataQuerySupport.findNode(this, type, serial);
	}

	/**
	 * 查询当前运行态仍在线的节点快照。
	 * <p>
	 * 该入口保留“严格在线真值”语义，适用于低频命令/诊断查询；
	 * 主线程敏感热路径请改用 `probeRuntimeOnlineNodeNonBlocking(...)`。
	 * </p>
	 */
	public Optional<LinkNode> findRuntimeOnlineNode(ServerLevel contextLevel, LinkNodeType type, long serial) {
		return LinkSavedDataQuerySupport.findRuntimeOnlineNode(this, contextLevel, type, serial);
	}

	/**
	 * 以非阻塞方式探测节点当前是否已真正就绪。
	 * <p>
	 * 该入口不会触发阻塞式取块，适用于 `CHUNK_LOAD`、tick 消费等热路径。
	 * </p>
	 */
	public RuntimeOnlineProbeResult probeRuntimeOnlineNodeNonBlocking(
		ServerLevel contextLevel,
		LinkNodeType type,
		long serial
	) {
		return LinkSavedDataQuerySupport.probeRuntimeOnlineNodeNonBlocking(this, contextLevel, type, serial);
	}

	/**
	 * 获取运行态在线节点拓扑版本。
	 * <p>
	 * 仅在节点坐标注册、移除或退役导致在线拓扑变化时递增，用于 resident 票据同步的脏检查。
	 * </p>
	 */
	public long runtimeNodeVersion() {
		return runtimeNodeVersion;
	}

	/**
	 * 获取当前连接图的运行时版本号。
	 * <p>
	 * 仅在 `triggerSource -> core` 拓扑真实发生变化时递增，不参与存档持久化。
	 * </p>
	 */
	public long graphRevision() {
		return graphRevision;
	}

	/**
	 * 获取指定来源节点当前的运行时版本号。
	 * <p>
	 * 当前只对 `triggerSource` 维护来源级 revision；`core` 视角固定返回 `0`，
	 * `core` 侧的 OCC 基线由独立的 `coreRevision` 提供。
	 * </p>
	 */
	public long sourceRevision(LinkNodeType sourceType, long sourceSerial) {
		if (sourceType != LinkNodeType.TRIGGER_SOURCE || sourceSerial <= 0L) {
			return 0L;
		}
		return triggerSourceRevisions.getOrDefault(sourceSerial, 0L);
	}

	/**
	 * 获取指定 core 当前的运行时版本号。
	 * <p>
	 * 仅在该 core 的成员集合真实发生变化时递增，用于 `core` 视角 OCC 精细化校验。
	 * </p>
	 */
	public long coreRevision(long coreSerial) {
		if (coreSerial <= 0L) {
			return 0L;
		}
		return coreRevisions.getOrDefault(coreSerial, 0L);
	}

	/**
	 * 判断序列号是否已登记分配。
	 */
	public boolean isSerialAllocated(LinkNodeType type, long serial) {
		return LinkSavedDataSerialSupport.isSerialAllocated(this, type, serial);
	}

	/**
	 * 判断序列号是否已退役。
	 */
	public boolean isSerialRetired(LinkNodeType type, long serial) {
		return LinkSavedDataSerialSupport.isSerialRetired(this, type, serial);
	}

	/**
	 * 判断序列号是否处于可用激活状态。
	 */
	public boolean isSerialActive(LinkNodeType type, long serial) {
		return LinkSavedDataSerialSupport.isSerialActive(this, type, serial);
	}

	/**
	 * 手动登记序列号为“已分配”。
	 */
	public boolean markSerialAllocated(LinkNodeType type, long serial) {
		return LinkSavedDataSerialSupport.markSerialAllocated(this, type, serial);
	}

	/**
	 * 将指定序号标记为转发器统一序号。
	 */
	public boolean markRepeaterSerial(long serial) {
		return LinkSavedDataSerialSupport.markRepeaterSerial(this, serial);
	}

	/**
	 * 将指定序号从转发器统一序号集合中移除。
	 */
	public boolean unmarkRepeaterSerial(long serial) {
		return LinkSavedDataSerialSupport.unmarkRepeaterSerial(this, serial);
	}

	/**
	 * 判断指定序号是否登记为转发器统一序号。
	 */
	public boolean isRepeaterSerial(long serial) {
		return LinkSavedDataSerialSupport.isRepeaterSerial(this, serial);
	}

	/**
	 * 记录 triggerSource 最近一次真实 sync replay 快照。
	 */
	public void putTriggerSourceReplaySyncSnapshot(
		long triggerSourceSerial,
		com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta eventMeta,
		int signalStrength
	) {
		LinkSavedDataSerialSupport.putTriggerSourceReplaySyncSnapshot(this, triggerSourceSerial, eventMeta, signalStrength);
	}

	/**
	 * 查询 triggerSource 最近一次已持久化的 sync replay 快照。
	 */
	public Optional<ReplaySyncSnapshotRecord> getTriggerSourceReplaySyncSnapshot(long triggerSourceSerial) {
		return LinkSavedDataQuerySupport.getTriggerSourceReplaySyncSnapshot(this, triggerSourceSerial);
	}

	/**
	 * 查询节点当前连接模式。
	 */
	public LinkConnectionMode getConnectionMode(LinkNodeType type, long serial) {
		return LinkSavedDataChannelSupport.getConnectionMode(this, type, serial);
	}

	/**
	 * 查询节点当前频道号；非频道模式返回 0。
	 */
	public long getChannel(LinkNodeType type, long serial) {
		return LinkSavedDataChannelSupport.getChannel(this, type, serial);
	}

	/**
	 * 查询指定频道下的节点成员集合。
	 */
	public Set<Long> getChannelMembers(LinkNodeType type, long channel) {
		return LinkSavedDataChannelSupport.getChannelMembers(this, type, channel);
	}

	/**
	 * 获取指定节点类型的活跃序列号集合。
	 */
	public Set<Long> getActiveSerials(LinkNodeType type) {
		return LinkSavedDataQuerySupport.getActiveSerials(this, type);
	}

	/**
	 * 获取指定节点类型的退役序列号集合。
	 */
	public Set<Long> getRetiredSerials(LinkNodeType type) {
		return LinkSavedDataQuerySupport.getRetiredSerials(this, type);
	}

	/**
	 * 获取指定节点类型的在线序列号集合。
	 */
	public Set<Long> getOnlineSerials(LinkNodeType type) {
		return LinkSavedDataQuerySupport.getOnlineSerials(this, type);
	}

	/**
	 * 切换 triggerSource 与 core 之间的关联关系。
	 */
	public boolean toggleTriggerSourceCoreLink(long triggerSourceSerial, long coreSerial) {
		return LinkSavedDataLinkIndexSupport.toggleTriggerSourceCoreLink(this, triggerSourceSerial, coreSerial);
	}

	/**
	 * 新增一条 triggerSource -> core 关联关系。
	 */
	public boolean addTriggerSourceCoreLink(long triggerSourceSerial, long coreSerial) {
		return LinkSavedDataLinkIndexSupport.addTriggerSourceCoreLink(this, triggerSourceSerial, coreSerial);
	}

	/**
	 * 移除一条 triggerSource -> core 关联关系。
	 */
	public boolean removeTriggerSourceCoreLink(long triggerSourceSerial, long coreSerial) {
		return LinkSavedDataLinkIndexSupport.removeTriggerSourceCoreLink(this, triggerSourceSerial, coreSerial);
	}

	/**
	 * 以“覆盖集合”语义增量替换 triggerSource 的 core 目标集合。
	 */
	public ReplaceLinksResult replaceTriggerSourceTargets(long triggerSourceSerial, Set<Long> coreSerials) {
		return LinkSavedDataLinkIndexSupport.replaceTriggerSourceTargets(this, triggerSourceSerial, coreSerials);
	}

	/**
	 * 查询 triggerSource 关联的 core 序列号集合。
	 */
	public Set<Long> getLinkedCoresByTriggerSource(long triggerSourceSerial) {
		return LinkSavedDataLinkIndexSupport.getLinkedCoresByTriggerSource(this, triggerSourceSerial);
	}

	/**
	 * 查询 core 被哪些 triggerSource 关联。
	 */
	public Set<Long> getLinkedTriggerSourcesByCore(long coreSerial) {
		return LinkSavedDataLinkIndexSupport.getLinkedTriggerSourcesByCore(this, coreSerial);
	}

	/**
	 * 按节点类型查询其关联的对侧节点集合。
	 * <p>
	 * 返回值始终为稳定快照，避免把内部可变集合继续外泄到事件链或外部调用方。
	 * </p>
	 */
	public Set<Long> getLinkedPeersByNodeType(LinkNodeType nodeType, long serial) {
		return LinkSavedDataLinkIndexSupport.getLinkedPeersByNodeType(this, nodeType, serial);
	}

	/**
	 * 按节点类型无拷贝遍历其关联的对侧节点集合。
	 */
	public void forEachLinkedPeerByNodeType(LinkNodeType nodeType, long serial, LongConsumer consumer) {
		LinkSavedDataLinkIndexSupport.forEachLinkedPeerByNodeType(this, nodeType, serial, consumer);
	}

	/**
	 * 清理指定节点的全部关联关系。
	 */
	public int clearLinksForNode(LinkNodeType type, long serial) {
		return LinkSavedDataLinkIndexSupport.clearLinksForNode(this, type, serial);
	}

	/**
	 * 生成当前链路拓扑审计快照。
	 */
	public AuditSnapshot createAuditSnapshot() {
		return LinkSavedDataQuerySupport.createAuditSnapshot(this);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		return LinkSavedDataCodecSupport.save(this, tag);
	}

	/**
	 * 按类型获取在线节点表。
	 */
	Map<Long, LinkNode> nodeMap(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? triggerSourceNodes : coreNodes;
	}

	/**
	 * 按类型获取已分配序列号集合。
	 */
	Set<Long> allocatedSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? allocatedTriggerSourceSerials : allocatedCoreSerials;
	}

	/**
	 * 按类型获取退役序列号集合。
	 */
	Set<Long> retiredSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? retiredTriggerSourceSerials : retiredCoreSerials;
	}

	/**
	 * 获取转发器统一序号集合。
	 */
	Set<Long> repeaterSerialSet() {
		return repeaterSerials;
	}

	/**
	 * 运行态在线节点拓扑发生变化时推进版本。
	 */
	void bumpRuntimeNodeVersion() {
		runtimeNodeVersion++;
	}

	/**
	 * 图拓扑真实变更时推进全图 revision。
	 */
	void bumpGraphRevision() {
		graphRevision++;
	}

	/**
	 * 指定 triggerSource 拓扑真实变更时推进来源 revision。
	 */
	void bumpTriggerSourceRevision(long triggerSourceSerial) {
		if (triggerSourceSerial <= 0L) {
			return;
		}
		triggerSourceRevisions.put(triggerSourceSerial, triggerSourceRevisions.getOrDefault(triggerSourceSerial, 0L) + 1L);
	}

	/**
	 * 指定 core 成员集合真实变更时推进 core revision。
	 */
	void bumpCoreRevision(long coreSerial) {
		if (coreSerial <= 0L) {
			return;
		}
		coreRevisions.put(coreSerial, coreRevisions.getOrDefault(coreSerial, 0L) + 1L);
	}

	/**
	 * 重建当前已登记节点的运行时区块索引。
	 * <p>
	 * 该索引只存在于内存中，用于过滤器变化等局部扫描场景快速定位候选节点。
	 * </p>
	 */
	void rebuildNodeChunkIndex() {
		nodeChunkIndex.clear();
		for (LinkNode node : triggerSourceNodes.values()) {
			indexNode(node);
		}
		for (LinkNode node : coreNodes.values()) {
			indexNode(node);
		}
	}

	/**
	 * 重建当前频道配置的运行时桶索引。
	 */
	void rebuildChannelIndex() {
		LinkSavedDataChannelSupport.rebuildChannelIndex(this);
	}

	/**
	 * 将一个已登记节点写入运行时区块索引。
	 */
	void indexNode(LinkNode node) {
		if (node == null || node.dimension() == null || node.type() == null || node.serial() <= 0L || node.pos() == null) {
			return;
		}
		long chunkKey = new ChunkPos(node.pos()).toLong();
		nodeChunkIndex
			.computeIfAbsent(node.dimension(), ignored -> new HashMap<>())
			.computeIfAbsent(node.type(), ignored -> new HashMap<>())
			.computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>())
			.add(node.serial());
	}

	/**
	 * 从运行时区块索引中移除一个已登记节点。
	 */
	void unindexNode(LinkNode node) {
		if (node == null || node.dimension() == null || node.type() == null || node.serial() <= 0L || node.pos() == null) {
			return;
		}
		Map<LinkNodeType, Map<Long, Set<Long>>> indexByType = nodeChunkIndex.get(node.dimension());
		if (indexByType == null) {
			return;
		}
		Map<Long, Set<Long>> indexByChunk = indexByType.get(node.type());
		if (indexByChunk == null) {
			return;
		}
		Set<Long> serials = indexByChunk.get(new ChunkPos(node.pos()).toLong());
		if (serials == null) {
			return;
		}
		serials.remove(node.serial());
		if (serials.isEmpty()) {
			indexByChunk.remove(new ChunkPos(node.pos()).toLong());
		}
		if (indexByChunk.isEmpty()) {
			indexByType.remove(node.type());
		}
		if (indexByType.isEmpty()) {
			nodeChunkIndex.remove(node.dimension());
		}
	}

	/**
	 * 按立方域收集当前已登记节点候选。
	 * <p>
	 * 该查询先按区块桶裁剪，再按真实坐标二次过滤，供过滤器变化扫描复用。
	 * </p>
	 */
	Set<LinkNode> collectNodesInCube(ResourceKey<Level> dimension, LinkNodeType type, BlockPos centerPos, int radius) {
		if (dimension == null || type == null || centerPos == null || radius < 0) {
			return Set.of();
		}
		Map<LinkNodeType, Map<Long, Set<Long>>> indexByType = nodeChunkIndex.get(dimension);
		if (indexByType == null) {
			return Set.of();
		}
		Map<Long, Set<Long>> indexByChunk = indexByType.get(type);
		if (indexByChunk == null || indexByChunk.isEmpty()) {
			return Set.of();
		}
		int minChunkX = SectionPos.blockToSectionCoord(centerPos.getX() - radius);
		int maxChunkX = SectionPos.blockToSectionCoord(centerPos.getX() + radius);
		int minChunkZ = SectionPos.blockToSectionCoord(centerPos.getZ() - radius);
		int maxChunkZ = SectionPos.blockToSectionCoord(centerPos.getZ() + radius);
		LinkedHashSet<LinkNode> nodes = new LinkedHashSet<>();
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				Set<Long> serials = indexByChunk.get(new ChunkPos(chunkX, chunkZ).toLong());
				if (serials == null || serials.isEmpty()) {
					continue;
				}
				for (Long serial : serials) {
					if (serial == null || serial <= 0L) {
						continue;
					}
					LinkNode node = nodeMap(type).get(serial);
					if (node == null || !isInsideCube(node.pos(), centerPos, radius)) {
						continue;
					}
					nodes.add(node);
				}
			}
		}
		return nodes.isEmpty() ? Set.of() : Set.copyOf(nodes);
	}

	/**
	 * 判断节点是否位于给定立方域内。
	 */
	private static boolean isInsideCube(BlockPos nodePos, BlockPos centerPos, int radius) {
		if (nodePos == null || centerPos == null || radius < 0) {
			return false;
		}
		return Math.abs(nodePos.getX() - centerPos.getX()) <= radius
			&& Math.abs(nodePos.getY() - centerPos.getY()) <= radius
			&& Math.abs(nodePos.getZ() - centerPos.getZ()) <= radius;
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
		int onlineTriggerSourceNodes,
		int totalLinks,
		int linksWithMissingEndpoint,
		int linkedTriggerSourceSerialCount,
		int linkedCoreSerialCount
	) {}

	/**
	 * 覆盖式替换链接结果记录。
	 */
	public record ReplaceLinksResult(int currentCount, int addedCount, int removedCount, int changedCount) {}

	/**
	 * 非阻塞在线探针状态。
	 */
	public enum RuntimeOnlineProbeStatus {
		READY,
		NOT_READY,
		MISMATCH,
		MISSING
	}

	/**
	 * 非阻塞在线探针结果。
	 */
	public record RuntimeOnlineProbeResult(RuntimeOnlineProbeStatus status, LinkNode node) {
		public RuntimeOnlineProbeResult {
			status = status == null ? RuntimeOnlineProbeStatus.MISSING : status;
		}

		static RuntimeOnlineProbeResult ready(LinkNode node) {
			return new RuntimeOnlineProbeResult(RuntimeOnlineProbeStatus.READY, node);
		}

		static RuntimeOnlineProbeResult notReady(LinkNode node) {
			return new RuntimeOnlineProbeResult(RuntimeOnlineProbeStatus.NOT_READY, node);
		}

		static RuntimeOnlineProbeResult mismatch(LinkNode node) {
			return new RuntimeOnlineProbeResult(RuntimeOnlineProbeStatus.MISMATCH, node);
		}

		static RuntimeOnlineProbeResult missing(LinkNode node) {
			return new RuntimeOnlineProbeResult(RuntimeOnlineProbeStatus.MISSING, node);
		}

		/**
		 * 当前是否可以立即安全消费。
		 */
		public boolean ready() {
			return status == RuntimeOnlineProbeStatus.READY;
		}

		/**
		 * 当前是否仅仅因为“尚未就绪”而需要短暂重试。
		 */
		public boolean retryable() {
			return status == RuntimeOnlineProbeStatus.NOT_READY;
		}
	}

	/**
	 * triggerSource 最近一次真实 sync replay 快照。
	 */
	public record ReplaySyncSnapshotRecord(
		int signalStrength,
		com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta eventMeta
	) {}
}
