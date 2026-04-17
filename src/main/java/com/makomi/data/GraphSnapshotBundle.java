package com.makomi.data;

import java.util.List;

/**
 * serial 模式图快照 bundle。
 * <p>
 * 该结构仅用于 `P3` 网页只读分析器导出，不写回世界真值，也不替代 `LinkSavedData`。
 * </p>
 */
public record GraphSnapshotBundle(
	String snapshotId,
	String mode,
	long graphRevision,
	long generatedAtTick,
	String viewerPlayerId,
	List<GraphNodeInfo> nodes,
	List<GraphEdgeInfo> edges,
	GraphStats stats
) {
	public GraphSnapshotBundle {
		snapshotId = normalizeText(snapshotId, "graph");
		mode = normalizeText(mode, "serial");
		graphRevision = Math.max(0L, graphRevision);
		generatedAtTick = Math.max(0L, generatedAtTick);
		viewerPlayerId = normalizeText(viewerPlayerId, "unknown");
		nodes = List.copyOf(nodes == null ? List.of() : nodes);
		edges = List.copyOf(edges == null ? List.of() : edges);
		stats = stats == null ? new GraphStats(0, 0, 0, 0, 0, 0, 0) : stats;
	}

	/**
	 * 单节点图快照信息。
	 */
	public record GraphNodeInfo(
		String nodeKey,
		LinkNodeType nodeType,
		long serial,
		String alias,
		String displayText,
		boolean allocated,
		boolean retired,
		boolean online,
		boolean active,
		int inputPower,
		int outputPower,
		String connectionMode,
		long channel,
		long sourceRevision,
		long coreRevision,
		List<String> capabilityFlags
	) {
		public GraphNodeInfo {
			nodeKey = normalizeText(nodeKey, "core:0");
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			alias = normalizeOptionalText(alias);
			displayText = normalizeText(displayText, NodeAliasDisplayUtil.formatDisplayText(alias, serial));
			inputPower = clampRedstonePower(inputPower);
			outputPower = clampRedstonePower(outputPower);
			connectionMode = normalizeText(connectionMode, LinkConnectionMode.SERIAL.token());
			channel = Math.max(0L, channel);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
			capabilityFlags = List.copyOf(capabilityFlags == null ? List.of() : capabilityFlags);
		}
	}

	/**
	 * 单条图边信息。
	 */
	public record GraphEdgeInfo(
		String edgeKey,
		String sourceNodeKey,
		String targetNodeKey,
		String kind,
		boolean readable,
		boolean editable
	) {
		public GraphEdgeInfo {
			edgeKey = normalizeText(edgeKey, "core:0->core:0");
			sourceNodeKey = normalizeText(sourceNodeKey, "core:0");
			targetNodeKey = normalizeText(targetNodeKey, "core:0");
			kind = normalizeText(kind, "serial");
		}
	}

	/**
	 * 图统计信息。
	 */
	public record GraphStats(
		int nodeCount,
		int edgeCount,
		int triggerSourceCount,
		int coreCount,
		int onlineNodeCount,
		int activeNodeCount,
		int maskedSourceCount
	) {
		public GraphStats {
			nodeCount = Math.max(0, nodeCount);
			edgeCount = Math.max(0, edgeCount);
			triggerSourceCount = Math.max(0, triggerSourceCount);
			coreCount = Math.max(0, coreCount);
			onlineNodeCount = Math.max(0, onlineNodeCount);
			activeNodeCount = Math.max(0, activeNodeCount);
			maskedSourceCount = Math.max(0, maskedSourceCount);
		}
	}

	private static String normalizeText(String rawText, String fallback) {
		if (rawText == null) {
			return fallback;
		}
		String normalized = rawText.trim();
		return normalized.isEmpty() ? fallback : normalized;
	}

	private static String normalizeOptionalText(String rawText) {
		return rawText == null ? "" : rawText.trim();
	}

	private static int clampRedstonePower(int power) {
		return Math.max(0, Math.min(15, power));
	}
}
