package com.makomi.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * 图快照 JSON 序列化支持。
 * <p>
 * 复用 recording 的“纯手写 JSON + gzip”策略，避免为离线网页资产单独引入新的运行时依赖。
 * </p>
 */
public final class GraphSnapshotJsonSupport {
	private static final int FILE_NAME_TOKEN_MAX_LENGTH = 24;

	private GraphSnapshotJsonSupport() {
	}

	/**
	 * 将图快照序列化为 JSON 文本。
	 */
	public static String toJson(GraphSnapshotBundle bundle) {
		GraphSnapshotBundle graphSnapshotBundle = bundle == null
			? new GraphSnapshotBundle("graph", "serial", 0L, 0L, "unknown", List.of(), List.of(), null)
			: bundle;
		StringBuilder builder = new StringBuilder(16384);
		builder.append('{');
		appendQuotedField(builder, "kind", "graphSnapshotBundle");
		builder.append(',');
		appendQuotedField(builder, "snapshotId", graphSnapshotBundle.snapshotId());
		builder.append(',');
		appendQuotedField(builder, "mode", graphSnapshotBundle.mode());
		builder.append(',');
		appendNumberField(builder, "graphRevision", graphSnapshotBundle.graphRevision());
		builder.append(',');
		appendNumberField(builder, "generatedAtTick", graphSnapshotBundle.generatedAtTick());
		builder.append(',');
		appendQuotedField(builder, "viewerPlayerId", graphSnapshotBundle.viewerPlayerId());
		builder.append(',');
		appendNodes(builder, graphSnapshotBundle.nodes());
		builder.append(',');
		appendEdges(builder, graphSnapshotBundle.edges());
		builder.append(',');
		appendStats(builder, graphSnapshotBundle.stats());
		builder.append('}');
		return builder.toString();
	}

	/**
	 * 将图快照序列化并压缩为 gzip 字节数组。
	 */
	public static byte[] toCompressedJsonBytes(GraphSnapshotBundle bundle) throws IOException {
		String json = toJson(bundle);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
			gzipOutputStream.write(json.getBytes(StandardCharsets.UTF_8));
		}
		return outputStream.toByteArray();
	}

	/**
	 * 为图快照生成稳定文件名。
	 */
	public static String buildFileName(GraphSnapshotBundle bundle) {
		GraphSnapshotBundle graphSnapshotBundle = bundle == null
			? new GraphSnapshotBundle("graph", "serial", 0L, 0L, "unknown", List.of(), List.of(), null)
			: bundle;
		String modeToken = sanitizeFileToken(graphSnapshotBundle.mode(), "serial");
		String snapshotToken = sanitizeFileToken(graphSnapshotBundle.snapshotId(), "graph");
		String snapshotSuffix = snapshotToken.length() <= 8
			? snapshotToken
			: snapshotToken.substring(snapshotToken.length() - 8);
		return "graph-%d-%s-r%d-%s.json.gz".formatted(
			graphSnapshotBundle.generatedAtTick(),
			modeToken,
			graphSnapshotBundle.graphRevision(),
			snapshotSuffix
		);
	}

	private static void appendNodes(StringBuilder builder, List<GraphSnapshotBundle.GraphNodeInfo> nodes) {
		builder.append("\"nodes\":[");
		for (int index = 0; index < nodes.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			GraphSnapshotBundle.GraphNodeInfo node = nodes.get(index);
			builder.append('{');
			appendQuotedField(builder, "nodeKey", node.nodeKey());
			builder.append(',');
			appendQuotedField(builder, "type", LinkNodeSemantics.toSemanticName(node.nodeType()));
			builder.append(',');
			appendNumberField(builder, "serial", node.serial());
			builder.append(',');
			appendQuotedField(builder, "alias", node.alias());
			builder.append(',');
			appendQuotedField(builder, "displayText", node.displayText());
			builder.append(',');
			appendBooleanField(builder, "allocated", node.allocated());
			builder.append(',');
			appendBooleanField(builder, "retired", node.retired());
			builder.append(',');
			appendBooleanField(builder, "online", node.online());
			builder.append(',');
			appendBooleanField(builder, "active", node.active());
			builder.append(',');
			appendNumberField(builder, "inputPower", node.inputPower());
			builder.append(',');
			appendNumberField(builder, "outputPower", node.outputPower());
			builder.append(',');
			appendQuotedField(builder, "connectionMode", node.connectionMode());
			builder.append(',');
			appendNumberField(builder, "channel", node.channel());
			builder.append(',');
			appendNumberField(builder, "sourceRevision", node.sourceRevision());
			builder.append(',');
			appendNumberField(builder, "coreRevision", node.coreRevision());
			builder.append(',');
			appendStringListField(builder, "capabilityFlags", node.capabilityFlags());
			builder.append('}');
		}
		builder.append(']');
	}

	private static void appendEdges(StringBuilder builder, List<GraphSnapshotBundle.GraphEdgeInfo> edges) {
		builder.append("\"edges\":[");
		for (int index = 0; index < edges.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			GraphSnapshotBundle.GraphEdgeInfo edge = edges.get(index);
			builder.append('{');
			appendQuotedField(builder, "edgeKey", edge.edgeKey());
			builder.append(',');
			appendQuotedField(builder, "sourceNodeKey", edge.sourceNodeKey());
			builder.append(',');
			appendQuotedField(builder, "targetNodeKey", edge.targetNodeKey());
			builder.append(',');
			appendQuotedField(builder, "kind", edge.kind());
			builder.append(',');
			appendBooleanField(builder, "readable", edge.readable());
			builder.append(',');
			appendBooleanField(builder, "editable", edge.editable());
			builder.append('}');
		}
		builder.append(']');
	}

	private static void appendStats(StringBuilder builder, GraphSnapshotBundle.GraphStats stats) {
		builder.append("\"stats\":{");
		appendNumberField(builder, "nodeCount", stats.nodeCount());
		builder.append(',');
		appendNumberField(builder, "edgeCount", stats.edgeCount());
		builder.append(',');
		appendNumberField(builder, "triggerSourceCount", stats.triggerSourceCount());
		builder.append(',');
		appendNumberField(builder, "coreCount", stats.coreCount());
		builder.append(',');
		appendNumberField(builder, "onlineNodeCount", stats.onlineNodeCount());
		builder.append(',');
		appendNumberField(builder, "activeNodeCount", stats.activeNodeCount());
		builder.append(',');
		appendNumberField(builder, "maskedSourceCount", stats.maskedSourceCount());
		builder.append('}');
	}

	private static void appendQuotedField(StringBuilder builder, String fieldName, String fieldValue) {
		builder.append('"').append(fieldName).append("\":");
		appendQuoted(builder, fieldValue);
	}

	private static void appendNumberField(StringBuilder builder, String fieldName, long fieldValue) {
		builder.append('"').append(fieldName).append("\":").append(fieldValue);
	}

	private static void appendBooleanField(StringBuilder builder, String fieldName, boolean fieldValue) {
		builder.append('"').append(fieldName).append("\":").append(fieldValue);
	}

	private static void appendStringListField(StringBuilder builder, String fieldName, List<String> values) {
		builder.append('"').append(fieldName).append("\":[");
		List<String> normalizedValues = values == null ? List.of() : List.copyOf(values);
		for (int index = 0; index < normalizedValues.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			appendQuoted(builder, normalizedValues.get(index));
		}
		builder.append(']');
	}

	private static void appendQuoted(StringBuilder builder, String rawValue) {
		builder.append('"').append(StatePanelRecordingJsonSupport.escapeJson(rawValue)).append('"');
	}

	private static String sanitizeFileToken(String rawText, String fallback) {
		String normalized = rawText == null ? "" : rawText.trim().toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(normalized.length());
		for (int index = 0; index < normalized.length(); index++) {
			char currentChar = normalized.charAt(index);
			if ((currentChar >= 'a' && currentChar <= 'z') || (currentChar >= '0' && currentChar <= '9')) {
				builder.append(currentChar);
			} else if (currentChar == '-' || currentChar == '_') {
				builder.append(currentChar);
			} else if (currentChar <= 0x7F) {
				builder.append('-');
			}
			if (builder.length() >= FILE_NAME_TOKEN_MAX_LENGTH) {
				break;
			}
		}
		String sanitized = builder.toString().replaceAll("-{2,}", "-").replaceAll("^[-_]+|[-_]+$", "");
		return sanitized.isEmpty() ? fallback : sanitized;
	}
}
