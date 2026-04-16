package com.makomi.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * 状态面板录制结果 JSON 序列化支持。
 * <p>
 * 当前仅负责 `P2` recording bundle 的稳定文本导出与 gzip 压缩，
 * 避免为该离线资产单独引入新的 JSON 运行时依赖。
 * </p>
 */
public final class StatePanelRecordingJsonSupport {
	private static final int FILE_NAME_TITLE_MAX_LENGTH = 32;

	private StatePanelRecordingJsonSupport() {
	}

	/**
	 * 将 recording bundle 序列化为 JSON 文本。
	 */
	public static String toJson(StatePanelRecordingBundle bundle) {
		StatePanelRecordingBundle recordingBundle = bundle == null
			? new StatePanelRecordingBundle(
				new StatePanelRecordingBundle.Manifest("recording", "State Panel Recording", 0L, 0L, 1, 0, 0, StatePanelRecordingBundle.FORMAT_VERSION),
				List.of(),
				List.of(),
				List.of()
			)
			: bundle;
		StringBuilder builder = new StringBuilder(8192);
		builder.append('{');
		appendQuotedField(builder, "kind", "recordingBundle");
		builder.append(',');
		appendManifest(builder, recordingBundle.manifest());
		builder.append(',');
		appendNodes(builder, recordingBundle.nodes());
		builder.append(',');
		appendSeries(builder, recordingBundle.series());
		builder.append(',');
		appendMarkers(builder, recordingBundle.markers());
		builder.append('}');
		return builder.toString();
	}

	/**
	 * 将 recording bundle 序列化并压缩为 gzip 字节数组。
	 */
	public static byte[] toCompressedJsonBytes(StatePanelRecordingBundle bundle) throws IOException {
		String json = toJson(bundle);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
			gzipOutputStream.write(json.getBytes(StandardCharsets.UTF_8));
		}
		return outputStream.toByteArray();
	}

	/**
	 * 为当前 recording bundle 生成稳定文件名。
	 */
	public static String buildFileName(StatePanelRecordingBundle bundle) {
		StatePanelRecordingBundle.Manifest manifest = bundle == null ? null : bundle.manifest();
		String titleToken = sanitizeFileToken(manifest == null ? null : manifest.title(), "recording");
		String recordingId = manifest == null ? "recording" : sanitizeFileToken(manifest.recordingId(), "recording");
		String idSuffix = recordingId.length() <= 8 ? recordingId : recordingId.substring(recordingId.length() - 8);
		long startedTick = manifest == null ? 0L : manifest.startedTick();
		return "recording-%d-%s-%s.json.gz".formatted(startedTick, titleToken, idSuffix);
	}

	private static void appendManifest(StringBuilder builder, StatePanelRecordingBundle.Manifest manifest) {
		builder.append("\"manifest\":{");
		appendQuotedField(builder, "recordingId", manifest.recordingId());
		builder.append(',');
		appendQuotedField(builder, "title", manifest.title());
		builder.append(',');
		appendNumberField(builder, "startedTick", manifest.startedTick());
		builder.append(',');
		appendNumberField(builder, "endedTick", manifest.endedTick());
		builder.append(',');
		appendNumberField(builder, "sampleEveryTicks", manifest.sampleEveryTicks());
		builder.append(',');
		appendNumberField(builder, "nodeCount", manifest.nodeCount());
		builder.append(',');
		appendNumberField(builder, "sampleCount", manifest.sampleCount());
		builder.append(',');
		appendNumberField(builder, "formatVersion", manifest.formatVersion());
		builder.append('}');
	}

	private static void appendNodes(StringBuilder builder, List<StatePanelRecordingBundle.RecordedNodeInfo> nodes) {
		builder.append("\"nodes\":[");
		for (int index = 0; index < nodes.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			StatePanelRecordingBundle.RecordedNodeInfo node = nodes.get(index);
			builder.append('{');
			appendQuotedField(builder, "nodeKey", node.nodeKey());
			builder.append(',');
			appendQuotedField(builder, "type", LinkNodeSemantics.toSemanticName(node.nodeType()));
			builder.append(',');
			appendNumberField(builder, "serial", node.serial());
			builder.append(',');
			appendQuotedField(builder, "displayText", node.displayText());
			builder.append(',');
			appendQuotedField(builder, "traceKind", node.traceKind());
			builder.append(',');
			appendBooleanField(builder, "allocated", node.allocated());
			builder.append(',');
			appendBooleanField(builder, "retired", node.retired());
			builder.append(',');
			appendBooleanField(builder, "online", node.online());
			builder.append('}');
		}
		builder.append(']');
	}

	private static void appendSeries(StringBuilder builder, List<StatePanelRecordingBundle.NodeSeries> seriesList) {
		builder.append("\"series\":[");
		for (int index = 0; index < seriesList.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			StatePanelRecordingBundle.NodeSeries nodeSeries = seriesList.get(index);
			builder.append('{');
			appendQuotedField(builder, "nodeKey", nodeSeries.nodeKey());
			builder.append(',');
			builder.append("\"samples\":[");
			for (int sampleIndex = 0; sampleIndex < nodeSeries.samples().size(); sampleIndex++) {
				if (sampleIndex > 0) {
					builder.append(',');
				}
				StatePanelRecordingBundle.RecordedSample sample = nodeSeries.samples().get(sampleIndex);
				builder.append('{');
				appendNumberField(builder, "tick", sample.tick());
				builder.append(',');
				appendBooleanField(builder, "online", sample.online());
				builder.append(',');
				appendBooleanField(builder, "active", sample.active());
				builder.append(',');
				appendNumberField(builder, "inputPower", sample.inputPower());
				builder.append(',');
				appendNumberField(builder, "outputPower", sample.outputPower());
				builder.append('}');
			}
			builder.append("]}");
		}
		builder.append(']');
	}

	private static void appendMarkers(StringBuilder builder, List<StatePanelRecordingBundle.RecordingMarker> markers) {
		builder.append("\"markers\":[");
		for (int index = 0; index < markers.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			StatePanelRecordingBundle.RecordingMarker marker = markers.get(index);
			builder.append('{');
			appendNumberField(builder, "tick", marker.tick());
			builder.append(',');
			appendQuotedField(builder, "label", marker.label());
			builder.append('}');
		}
		builder.append(']');
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

	private static void appendQuoted(StringBuilder builder, String rawValue) {
		builder.append('"').append(escapeJson(rawValue)).append('"');
	}

	/**
	 * 复用最小 JSON 字符串转义，覆盖当前 recording bundle 中可能出现的控制字符。
	 */
	public static String escapeJson(String rawValue) {
		if (rawValue == null || rawValue.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(rawValue.length() + 16);
		for (int index = 0; index < rawValue.length(); index++) {
			char currentChar = rawValue.charAt(index);
			switch (currentChar) {
				case '\\' -> builder.append("\\\\");
				case '"' -> builder.append("\\\"");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				default -> {
					if (currentChar <= 0x1F) {
						builder.append(String.format(Locale.ROOT, "\\u%04x", (int) currentChar));
					} else {
						builder.append(currentChar);
					}
				}
			}
		}
		return builder.toString();
	}

	private static String sanitizeFileToken(String rawText, String fallback) {
		String normalized = rawText == null ? "" : rawText.trim().toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(normalized.length());
		for (int index = 0; index < normalized.length(); index++) {
			char currentChar = normalized.charAt(index);
			if ((currentChar >= 'a' && currentChar <= 'z') || (currentChar >= '0' && currentChar <= '9')) {
				builder.append(currentChar);
				continue;
			}
			if (currentChar == '-' || currentChar == '_') {
				builder.append(currentChar);
			} else if (currentChar <= 0x7F) {
				builder.append('-');
			}
			if (builder.length() >= FILE_NAME_TITLE_MAX_LENGTH) {
				break;
			}
		}
		String sanitized = builder.toString().replaceAll("-{2,}", "-").replaceAll("^[-_]+|[-_]+$", "");
		return sanitized.isEmpty() ? fallback : sanitized;
	}
}
