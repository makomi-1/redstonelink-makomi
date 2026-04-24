package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * LinkSavedData 查询与审计视图 helper。
 * <p>
 * 负责对外只读查询、活跃/在线集合投影以及链路拓扑审计快照生成。
 * </p>
 */
final class LinkSavedDataQuerySupport {
	private LinkSavedDataQuerySupport() {
	}

	/**
	 * 查询节点快照。
	 */
	static Optional<LinkSavedData.LinkNode> findNode(LinkSavedData data, LinkNodeType type, long serial) {
		return Optional.ofNullable(data.nodeMap(type).get(serial));
	}

	/**
	 * 查询当前运行态仍在线的节点快照。
	 * <p>
	 * 这里的“在线”定义比 `findNode(...)` 更严格：
	 * 1. 已登记过位置；
	 * 2. 所在维度与区块当前已加载；
	 * 3. 该位置上的方块实体仍为同 `type + serial` 的节点。
	 * </p>
	 * <p>
	 * 该方法面向低频的“严格在线真值”查询，允许为了拿到权威结果而同步访问世界。
	 * 请不要直接在 `CHUNK_LOAD`、tick 消费等主线程敏感热路径中调用；
	 * 这些场景应改用 `probeRuntimeOnlineNodeNonBlocking(...)`。
	 * </p>
	 */
	static Optional<LinkSavedData.LinkNode> findRuntimeOnlineNode(
		LinkSavedData data,
		ServerLevel contextLevel,
		LinkNodeType type,
		long serial
	) {
		if (data == null || contextLevel == null || type == null || serial <= 0L) {
			return Optional.empty();
		}
		LinkSavedData.LinkNode node = data.nodeMap(type).get(serial);
		if (node == null) {
			return Optional.empty();
		}
		ServerLevel nodeLevel = contextLevel.getServer().getLevel(node.dimension());
		if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
			return Optional.empty();
		}
		BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return Optional.empty();
		}
		if (!pairableNodeBlockEntity.matchesNodeIdentity(type, serial)) {
			return Optional.empty();
		}
		return Optional.of(node);
	}

	/**
	 * 以“非阻塞就绪探针”方式检查节点是否在线。
	 * <p>
	 * 该探针只使用 `getChunkNow(...) + getBlockEntity(..., CHECK)`：
	 * 1. 当前 tick 能安全拿到同 `type + serial` 的节点时返回 `READY`；
	 * 2. 若区块/方块实体尚未完全就绪，则返回 `NOT_READY`；
	 * 3. 若已明确不是该节点，返回 `MISMATCH`；
	 * 4. 若存档侧已无该节点记录，返回 `MISSING`。
	 * </p>
	 */
	static LinkSavedData.RuntimeOnlineProbeResult probeRuntimeOnlineNodeNonBlocking(
		LinkSavedData data,
		ServerLevel contextLevel,
		LinkNodeType type,
		long serial
	) {
		if (data == null || contextLevel == null || type == null || serial <= 0L) {
			return LinkSavedData.RuntimeOnlineProbeResult.missing(null);
		}
		LinkSavedData.LinkNode node = data.nodeMap(type).get(serial);
		if (node == null) {
			return LinkSavedData.RuntimeOnlineProbeResult.missing(null);
		}
		ServerLevel nodeLevel = contextLevel.getServer().getLevel(node.dimension());
		if (nodeLevel == null) {
			return LinkSavedData.RuntimeOnlineProbeResult.missing(node);
		}
		LevelChunk nodeChunk = nodeLevel.getChunkSource().getChunkNow(node.pos().getX() >> 4, node.pos().getZ() >> 4);
		if (nodeChunk == null) {
			return LinkSavedData.RuntimeOnlineProbeResult.notReady(node);
		}
		BlockEntity blockEntity = nodeChunk.getBlockEntity(node.pos(), LevelChunk.EntityCreationType.CHECK);
		if (blockEntity == null) {
			return nodeChunk.getBlockState(node.pos()).hasBlockEntity()
				? LinkSavedData.RuntimeOnlineProbeResult.notReady(node)
				: LinkSavedData.RuntimeOnlineProbeResult.mismatch(node);
		}
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return LinkSavedData.RuntimeOnlineProbeResult.mismatch(node);
		}
		if (!pairableNodeBlockEntity.matchesNodeIdentity(type, serial)) {
			return LinkSavedData.RuntimeOnlineProbeResult.mismatch(node);
		}
		return LinkSavedData.RuntimeOnlineProbeResult.ready(node);
	}

	/**
	 * 获取指定节点类型的活跃序列号集合。
	 */
	static Set<Long> getActiveSerials(LinkSavedData data, LinkNodeType type) {
		Set<Long> active = new HashSet<>(data.allocatedSerialSet(type));
		active.removeAll(data.retiredSerialSet(type));
		return Set.copyOf(active);
	}

	/**
	 * 获取指定节点类型的退役序列号集合。
	 */
	static Set<Long> getRetiredSerials(LinkSavedData data, LinkNodeType type) {
		return Set.copyOf(data.retiredSerialSet(type));
	}

	/**
	 * 获取指定节点类型的在线序列号集合。
	 */
	static Set<Long> getOnlineSerials(LinkSavedData data, LinkNodeType type) {
		return Set.copyOf(data.nodeMap(type).keySet());
	}

	/**
	 * 查询 triggerSource 最近一次已持久化的 sync replay 快照。
	 */
	static Optional<LinkSavedData.ReplaySyncSnapshotRecord> getTriggerSourceReplaySyncSnapshot(
		LinkSavedData data,
		long triggerSourceSerial
	) {
		if (data == null || triggerSourceSerial <= 0L) {
			return Optional.empty();
		}
		return Optional.ofNullable(data.triggerSourceReplaySyncSnapshots.get(triggerSourceSerial));
	}

	/**
	 * 生成当前链路拓扑审计快照。
	 */
	static LinkSavedData.AuditSnapshot createAuditSnapshot(LinkSavedData data) {
		int linkCount = 0;
		int linksWithMissingEndpoint = 0;

		for (Map.Entry<Long, Set<Long>> entry : data.triggerSourceToCores.entrySet()) {
			boolean triggerSourceOnline = data.triggerSourceNodes.containsKey(entry.getKey());
			for (long coreSerial : entry.getValue()) {
				linkCount++;
				boolean coreOnline = data.coreNodes.containsKey(coreSerial);
				if (!triggerSourceOnline || !coreOnline) {
					linksWithMissingEndpoint++;
				}
			}
		}

		return new LinkSavedData.AuditSnapshot(
			data.coreNodes.size(),
			data.triggerSourceNodes.size(),
			linkCount,
			linksWithMissingEndpoint,
			data.triggerSourceToCores.size(),
			data.coreToTriggerSources.size()
		);
	}
}
