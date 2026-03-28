package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

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
		if (pairableNodeBlockEntity.getLinkNodeType() != type || pairableNodeBlockEntity.getSerial() != serial) {
			return Optional.empty();
		}
		return Optional.of(node);
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

		for (Map.Entry<Long, Set<Long>> entry : data.buttonToCores.entrySet()) {
			boolean triggerSourceOnline = data.buttonNodes.containsKey(entry.getKey());
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
			data.buttonNodes.size(),
			linkCount,
			linksWithMissingEndpoint,
			data.buttonToCores.size(),
			data.coreToButtons.size()
		);
	}
}
