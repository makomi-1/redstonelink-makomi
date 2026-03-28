package com.makomi.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
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
	static final String KEY_NEXT_SERIAL = "nextSerial";
	static final String KEY_NEXT_CORE_SERIAL = "nextCoreSerial";
	static final String KEY_NEXT_BUTTON_SERIAL = "nextButtonSerial";
	static final String KEY_NODES = "nodes";
	static final String KEY_SERIAL = "serial";
	static final String KEY_DIMENSION = "dimension";
	static final String KEY_POS = "pos";
	static final String KEY_TYPE = "type";
	static final String KEY_LINKS = "links";
	static final String KEY_SOURCE_SERIAL = "sourceSerial";
	static final String KEY_TARGET_SERIALS = "targetSerials";
	static final String KEY_ALLOCATED_CORE_SERIALS = "allocatedCoreSerials";
	static final String KEY_ALLOCATED_BUTTON_SERIALS = "allocatedButtonSerials";
	static final String KEY_RETIRED_CORE_SERIALS = "retiredCoreSerials";
	static final String KEY_RETIRED_BUTTON_SERIALS = "retiredButtonSerials";
	static final String KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS = "triggerSourceReplaySyncSnapshots";
	static final String KEY_SIGNAL_STRENGTH = "signalStrength";
	static final String KEY_TICK = "tick";
	static final String KEY_SLOT = "slot";
	static final String KEY_SEQ = "seq";

	private static final SavedData.Factory<LinkSavedData> FACTORY = new SavedData.Factory<>(
		LinkSavedData::new,
		LinkSavedData::load,
		DataFixTypes.LEVEL
	);

	long nextCoreSerial = 1L;
	long nextButtonSerial = 1L;
	final Map<Long, LinkNode> coreNodes = new HashMap<>();
	final Map<Long, LinkNode> buttonNodes = new HashMap<>();
	final Map<Long, Set<Long>> buttonToCores = new HashMap<>();
	final Map<Long, Set<Long>> coreToButtons = new HashMap<>();
	final Set<Long> allocatedCoreSerials = new HashSet<>();
	final Set<Long> allocatedButtonSerials = new HashSet<>();
	final Set<Long> retiredCoreSerials = new HashSet<>();
	final Set<Long> retiredButtonSerials = new HashSet<>();
	final Map<Long, ReplaySyncSnapshotRecord> triggerSourceReplaySyncSnapshots = new HashMap<>();

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
		return LinkSavedDataCodecSupport.load(tag, provider);
	}

	/**
	 * 为指定节点类型分配新序列号并立即标记为已分配。
	 */
	public long allocateSerial(LinkNodeType type) {
		return LinkSavedDataSerialSupport.allocateSerial(this, type);
	}

	/**
	 * 解析放置场景下最终可用的序列号。
	 */
	public long resolvePlacementSerial(LinkNodeType type, long preferredSerial, ResourceKey<Level> dimension, BlockPos pos) {
		return LinkSavedDataSerialSupport.resolvePlacementSerial(this, type, preferredSerial, dimension, pos);
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
	public boolean toggleLink(long buttonSerial, long coreSerial) {
		return LinkSavedDataLinkIndexSupport.toggleLink(this, buttonSerial, coreSerial);
	}

	/**
	 * 以“来源/目标”语义切换关联关系。
	 */
	public boolean toggleLinkBySourceType(LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		return LinkSavedDataLinkIndexSupport.toggleLinkBySourceType(this, sourceType, sourceSerial, targetSerial);
	}

	/**
	 * 以“来源/目标”语义新增单条关联关系。
	 */
	public boolean addLinkBySourceType(LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		return LinkSavedDataLinkIndexSupport.addLinkBySourceType(this, sourceType, sourceSerial, targetSerial);
	}

	/**
	 * 以“来源/目标”语义移除单条关联关系。
	 */
	public boolean removeLinkBySourceType(LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		return LinkSavedDataLinkIndexSupport.removeLinkBySourceType(this, sourceType, sourceSerial, targetSerial);
	}

	/**
	 * 以“覆盖集合”语义增量替换来源节点的目标集合。
	 */
	public ReplaceLinksResult replaceLinksBySourceType(LinkNodeType sourceType, long sourceSerial, Set<Long> targetSerials) {
		return LinkSavedDataLinkIndexSupport.replaceLinksBySourceType(this, sourceType, sourceSerial, targetSerials);
	}

	/**
	 * 解除 triggerSource 与 core 的单条关联关系。
	 */
	public boolean unlink(long buttonSerial, long coreSerial) {
		return LinkSavedDataLinkIndexSupport.unlink(this, buttonSerial, coreSerial);
	}

	/**
	 * 查询 triggerSource 关联的 core 序列号集合。
	 */
	public Set<Long> getLinkedCores(long buttonSerial) {
		return LinkSavedDataLinkIndexSupport.getLinkedCores(this, buttonSerial);
	}

	/**
	 * 查询 core 被哪些 triggerSource 关联。
	 */
	public Set<Long> getLinkedButtons(long coreSerial) {
		return LinkSavedDataLinkIndexSupport.getLinkedTriggerSources(this, coreSerial);
	}

	/**
	 * 按“来源类型 + 来源序列号”查询目标集合。
	 */
	public Set<Long> getLinkedTargetsBySourceType(LinkNodeType sourceType, long sourceSerial) {
		return LinkSavedDataLinkIndexSupport.getLinkedTargetsBySourceType(this, sourceType, sourceSerial);
	}

	/**
	 * 按“来源类型 + 来源序列号”无拷贝遍历目标集合。
	 */
	public void forEachLinkedTargetBySourceType(LinkNodeType sourceType, long sourceSerial, LongConsumer consumer) {
		LinkSavedDataLinkIndexSupport.forEachLinkedTargetBySourceType(this, sourceType, sourceSerial, consumer);
	}

	/**
	 * 返回内部目标集合视图（无拷贝）。
	 */
	Set<Long> linkedTargetsViewBySourceType(LinkNodeType sourceType, long sourceSerial) {
		return LinkSavedDataLinkIndexSupport.linkedTargetsViewBySourceType(this, sourceType, sourceSerial);
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
		return type == LinkNodeType.TRIGGER_SOURCE ? buttonNodes : coreNodes;
	}

	/**
	 * 按类型获取已分配序列号集合。
	 */
	Set<Long> allocatedSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? allocatedButtonSerials : allocatedCoreSerials;
	}

	/**
	 * 按类型获取退役序列号集合。
	 */
	Set<Long> retiredSerialSet(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? retiredButtonSerials : retiredCoreSerials;
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
