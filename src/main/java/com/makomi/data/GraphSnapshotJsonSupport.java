package com.makomi.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
	private static final String STRUCTURE_CHECKSUM_ALGORITHM = "SHA-256";
	private static final int STRUCTURE_CHECKSUM_SHORT_LENGTH = 12;
	private static final int FILE_NAME_TOKEN_MAX_LENGTH = 24;

	private GraphSnapshotJsonSupport() {
	}

	/**
	 * 将图快照序列化为 JSON 文本。
	 */
	public static String toJson(GraphSnapshotBundle bundle) {
		GraphSnapshotBundle graphSnapshotBundle = bundle == null
			? new GraphSnapshotBundle("graph", "serial", 0L, 0L, "unknown", "graph", List.of(), List.of(), null)
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
		appendQuotedField(builder, "structureChecksum", resolveStructureChecksum(graphSnapshotBundle));
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
			? new GraphSnapshotBundle("graph", "serial", 0L, 0L, "unknown", "graph", List.of(), List.of(), null)
			: bundle;
		String modeToken = sanitizeFileToken(graphSnapshotBundle.mode(), "serial");
		return "graph-%s-r%d-%s.json.gz".formatted(
			modeToken,
			graphSnapshotBundle.graphRevision(),
			shortChecksum(resolveStructureChecksum(graphSnapshotBundle))
		);
	}

	/**
	 * 基于稳定结构语义生成图快照校验码。
	 * <p>
	 * 仅纳入 triggerSource/core 结构字段，不包含运行态、玩家与 tick 等波动信息。
	 * </p>
	 */
	public static String buildStructureChecksum(GraphSnapshotBundle bundle) {
		GraphSnapshotBundle graphSnapshotBundle = bundle == null
			? new GraphSnapshotBundle("graph", "serial", 0L, 0L, "unknown", "graph", List.of(), List.of(), null)
			: bundle;
		StringBuilder builder = new StringBuilder(8192);
		appendFingerprintText(builder, graphSnapshotBundle.mode());
		builder.append('\n');
		builder.append(graphSnapshotBundle.stats().maskedSourceCount()).append('\n');
		graphSnapshotBundle
			.nodes()
			.stream()
			.sorted(
				java.util.Comparator
					.comparing(GraphSnapshotBundle.GraphNodeInfo::nodeKey)
					.thenComparingLong(GraphSnapshotBundle.GraphNodeInfo::serial)
			)
			.forEach(node -> appendNodeFingerprint(builder, node));
		graphSnapshotBundle
			.edges()
			.stream()
			.sorted(
				java.util.Comparator
					.comparing(GraphSnapshotBundle.GraphEdgeInfo::sourceNodeKey)
					.thenComparing(GraphSnapshotBundle.GraphEdgeInfo::targetNodeKey)
					.thenComparing(GraphSnapshotBundle.GraphEdgeInfo::kind)
					.thenComparing(GraphSnapshotBundle.GraphEdgeInfo::edgeKey)
			)
			.forEach(edge -> appendEdgeFingerprint(builder, edge));
		try {
			MessageDigest digest = MessageDigest.getInstance(STRUCTURE_CHECKSUM_ALGORITHM);
			return HexFormat.of().formatHex(digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("missing checksum algorithm: " + STRUCTURE_CHECKSUM_ALGORITHM, exception);
		}
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

	private static void appendNodeFingerprint(StringBuilder builder, GraphSnapshotBundle.GraphNodeInfo node) {
		appendFingerprintText(builder, node.nodeKey());
		builder.append('|');
		appendFingerprintText(builder, LinkNodeSemantics.toSemanticName(node.nodeType()));
		builder.append('|').append(node.serial());
		builder.append('|');
		appendFingerprintText(builder, node.alias());
		builder.append('|');
		appendFingerprintText(builder, node.displayText());
		builder.append('|').append(node.allocated());
		builder.append('|').append(node.retired());
		builder.append('|');
		appendFingerprintText(builder, node.connectionMode());
		builder.append('|').append(node.channel());
		builder.append('|').append(node.sourceRevision());
		builder.append('|').append(node.coreRevision());
		builder.append('|');
		List<String> capabilityFlags = node.capabilityFlags() == null ? List.of() : node.capabilityFlags().stream().sorted().toList();
		for (int index = 0; index < capabilityFlags.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			appendFingerprintText(builder, capabilityFlags.get(index));
		}
		builder.append('\n');
	}

	private static void appendEdgeFingerprint(StringBuilder builder, GraphSnapshotBundle.GraphEdgeInfo edge) {
		appendFingerprintText(builder, edge.sourceNodeKey());
		builder.append('|');
		appendFingerprintText(builder, edge.targetNodeKey());
		builder.append('|');
		appendFingerprintText(builder, edge.kind());
		builder.append('|').append(edge.readable());
		builder.append('|').append(edge.editable());
		builder.append('\n');
	}

	private static void appendFingerprintText(StringBuilder builder, String rawValue) {
		builder.append(StatePanelRecordingJsonSupport.escapeJson(rawValue == null ? "" : rawValue));
	}

	private static String resolveStructureChecksum(GraphSnapshotBundle bundle) {
		if (bundle == null || bundle.structureChecksum() == null || bundle.structureChecksum().isBlank()) {
			return buildStructureChecksum(bundle);
		}
		return bundle.structureChecksum();
	}

	private static String shortChecksum(String checksum) {
		String normalized = sanitizeFileToken(checksum, "graph");
		return normalized.length() <= STRUCTURE_CHECKSUM_SHORT_LENGTH
			? normalized
			: normalized.substring(0, STRUCTURE_CHECKSUM_SHORT_LENGTH);
	}
}
