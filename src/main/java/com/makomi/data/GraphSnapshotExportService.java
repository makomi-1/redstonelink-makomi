package com.makomi.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 图快照导出服务。
 * <p>
 * 当前仅导出当前玩家可读的 `serial` 视图，用于网页端只读分析器。
 * </p>
 */
public final class GraphSnapshotExportService {
	private static final String MODE_SERIAL = "serial";

	private GraphSnapshotExportService() {
	}

	/**
	 * 导出当前玩家可见的 serial 图快照，并附带压缩字节与文件名。
	 */
	public static ExportBundle exportVisibleSerialGraph(ServerPlayer player) throws IOException {
		GraphSnapshotBundle bundle = buildVisibleSerialGraph(player);
		return new ExportBundle(bundle, GraphSnapshotJsonSupport.buildFileName(bundle), GraphSnapshotJsonSupport.toCompressedJsonBytes(bundle));
	}

	/**
	 * 构建当前玩家可见的 serial 图快照。
	 */
	public static GraphSnapshotBundle buildVisibleSerialGraph(ServerPlayer player) {
		if (player == null) {
			return emptyBundle();
		}
		ServerLevel level = player.serverLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		long generatedAtTick = level.getGameTime();
		long graphRevision = savedData.graphRevision();
		String snapshotId = buildSnapshotId(generatedAtTick, player.getUUID());
		Map<String, GraphSnapshotBundle.GraphNodeInfo> nodesByKey = new LinkedHashMap<>();
		Set<GraphSnapshotBundle.GraphEdgeInfo> edgeSet = new LinkedHashSet<>();
		int maskedSourceCount = 0;

		for (long serial : sortedSerials(savedData.getActiveSerials(LinkNodeType.TRIGGER_SOURCE))) {
			if (!CurrentLinksPrivacyService.canReadNodeState(player, LinkNodeType.TRIGGER_SOURCE, serial)) {
				continue;
			}
			GraphSnapshotBundle.GraphNodeInfo sourceNode = buildNodeInfo(level, savedData, LinkNodeType.TRIGGER_SOURCE, serial);
			nodesByKey.put(sourceNode.nodeKey(), sourceNode);

			NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(player, LinkNodeType.TRIGGER_SOURCE, serial);
			if (linksSnapshot.masked()) {
				maskedSourceCount++;
			}
			for (Long targetSerialValue : linksSnapshot.visibleTargets()) {
				long targetSerial = targetSerialValue == null ? 0L : targetSerialValue;
				if (targetSerial <= 0L) {
					continue;
				}
				if (!CurrentLinksPrivacyService.canReadNodeState(player, LinkNodeType.CORE, targetSerial)) {
					continue;
				}
				GraphSnapshotBundle.GraphNodeInfo targetNode = nodesByKey.computeIfAbsent(
					nodeKey(LinkNodeType.CORE, targetSerial),
					ignored -> buildNodeInfo(level, savedData, LinkNodeType.CORE, targetSerial)
				);
				edgeSet.add(
					new GraphSnapshotBundle.GraphEdgeInfo(
						sourceNode.nodeKey() + "->" + targetNode.nodeKey(),
						sourceNode.nodeKey(),
						targetNode.nodeKey(),
						MODE_SERIAL,
						true,
						false
					)
				);
			}
		}

		for (long serial : sortedSerials(savedData.getActiveSerials(LinkNodeType.CORE))) {
			if (!CurrentLinksPrivacyService.canReadNodeState(player, LinkNodeType.CORE, serial)) {
				continue;
			}
			nodesByKey.computeIfAbsent(nodeKey(LinkNodeType.CORE, serial), ignored -> buildNodeInfo(level, savedData, LinkNodeType.CORE, serial));
		}

		List<GraphSnapshotBundle.GraphNodeInfo> nodes = new ArrayList<>(nodesByKey.values());
		nodes.sort(
			java.util.Comparator
				.comparing((GraphSnapshotBundle.GraphNodeInfo node) -> LinkNodeSemantics.toSemanticName(node.nodeType()))
				.thenComparingLong(GraphSnapshotBundle.GraphNodeInfo::serial)
		);
		List<GraphSnapshotBundle.GraphEdgeInfo> edges = new ArrayList<>(edgeSet);
		edges.sort(
			java.util.Comparator
				.comparing(GraphSnapshotBundle.GraphEdgeInfo::sourceNodeKey)
				.thenComparing(GraphSnapshotBundle.GraphEdgeInfo::targetNodeKey)
		);

		int triggerSourceCount = 0;
		int coreCount = 0;
		int onlineNodeCount = 0;
		int activeNodeCount = 0;
		for (GraphSnapshotBundle.GraphNodeInfo node : nodes) {
			if (node.nodeType() == LinkNodeType.TRIGGER_SOURCE) {
				triggerSourceCount++;
			} else {
				coreCount++;
			}
			if (node.online()) {
				onlineNodeCount++;
			}
			if (node.active()) {
				activeNodeCount++;
			}
		}

		return new GraphSnapshotBundle(
			snapshotId,
			MODE_SERIAL,
			graphRevision,
			generatedAtTick,
			player.getUUID().toString(),
			nodes,
			edges,
			new GraphSnapshotBundle.GraphStats(
				nodes.size(),
				edges.size(),
				triggerSourceCount,
				coreCount,
				onlineNodeCount,
				activeNodeCount,
				maskedSourceCount
			)
		);
	}

	private static GraphSnapshotBundle emptyBundle() {
		return new GraphSnapshotBundle("graph-empty", MODE_SERIAL, 0L, 0L, "unknown", List.of(), List.of(), null);
	}

	/**
	 * 组装单节点图快照信息。
	 */
	private static GraphSnapshotBundle.GraphNodeInfo buildNodeInfo(
		ServerLevel level,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		NodeRuntimeSnapshot runtimeSnapshot = NodeSnapshotQueryService.resolveRuntimeSnapshot(level.getServer(), nodeType, serial).orElse(null);
		String alias = NodeAliasServerSupport.resolveAlias(level, nodeType, serial).orElse("");
		LinkConnectionMode connectionMode = savedData.getConnectionMode(nodeType, serial);
		return new GraphSnapshotBundle.GraphNodeInfo(
			nodeKey(nodeType, serial),
			nodeType,
			serial,
			alias,
			NodeAliasDisplayUtil.formatDisplayText(alias, serial),
			identity.allocated(),
			identity.retired(),
			runtimeSnapshot != null ? runtimeSnapshot.online() : identity.online(),
			runtimeSnapshot != null && runtimeSnapshot.active(),
			runtimeSnapshot == null ? 0 : runtimeSnapshot.inputPower(),
			runtimeSnapshot == null ? 0 : runtimeSnapshot.outputPower(),
			connectionMode.token(),
			savedData.getChannel(nodeType, serial),
			savedData.sourceRevision(nodeType, serial),
			nodeType == LinkNodeType.CORE ? savedData.coreRevision(serial) : 0L,
			resolveCapabilityFlags(nodeType, connectionMode)
		);
	}

	private static List<String> resolveCapabilityFlags(LinkNodeType nodeType, LinkConnectionMode connectionMode) {
		List<String> capabilityFlags = new ArrayList<>(3);
		capabilityFlags.add(nodeType == LinkNodeType.TRIGGER_SOURCE ? "outbound" : "inbound");
		capabilityFlags.add("readonly");
		if (connectionMode == LinkConnectionMode.CHANNEL) {
			capabilityFlags.add("channel");
		}
		return List.copyOf(capabilityFlags);
	}

	private static List<Long> sortedSerials(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		return serials.stream().filter(serial -> serial != null && serial > 0L).sorted().toList();
	}

	private static String nodeKey(LinkNodeType nodeType, long serial) {
		return LinkNodeSemantics.toSemanticName(nodeType) + ":" + Math.max(0L, serial);
	}

	private static String buildSnapshotId(long generatedAtTick, UUID playerId) {
		String rawPlayerId = playerId == null ? "player" : playerId.toString().replace("-", "");
		String suffix = rawPlayerId.length() <= 8 ? rawPlayerId : rawPlayerId.substring(0, 8);
		return "serial-%d-%s".formatted(Math.max(0L, generatedAtTick), suffix);
	}

	/**
	 * 图快照导出结果。
	 */
	public record ExportBundle(GraphSnapshotBundle bundle, String fileName, byte[] compressedBytes) {
		public ExportBundle {
			bundle = bundle == null ? emptyBundle() : bundle;
			fileName = fileName == null ? GraphSnapshotJsonSupport.buildFileName(bundle) : fileName;
			compressedBytes = compressedBytes == null ? new byte[0] : compressedBytes.clone();
		}

		@Override
		public byte[] compressedBytes() {
			return compressedBytes.clone();
		}
	}
}
