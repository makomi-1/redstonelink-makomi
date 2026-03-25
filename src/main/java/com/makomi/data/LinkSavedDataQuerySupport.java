package com.makomi.data;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
