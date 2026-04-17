package com.makomi.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * graph 编辑保存 JSON 协议支撑。
 * <p>
 * 该支撑层只负责：
 * </p>
 * <ul>
 * <li>解析网页端提交的结构化保存请求；</li>
 * <li>构建服务端返回给网页端的保存结果 JSON；</li>
 * <li>保持 `triggerSource/core` 语义命名收口，不向协议外泄旧术语。</li>
 * </ul>
 */
public final class GraphWriteJsonSupport {
	private static final String MODE_SERIAL = "serial";
	private static final String TYPE_RENAME_NODE_ALIAS = "RenameNodeAlias";
	private static final String TYPE_REPLACE_TRIGGER_SOURCE_TARGETS = "ReplaceTriggerSourceTargets";

	private GraphWriteJsonSupport() {
	}

	/**
	 * 解析网页端保存请求。
	 */
	public static ParseResult parseRequest(String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			return ParseResult.failure(buildRejectedResponse("invalid_request", "保存请求为空。", 0L, List.of()));
		}
		try {
			JsonElement rootElement = JsonParser.parseString(rawJson);
			if (!rootElement.isJsonObject()) {
				return ParseResult.failure(buildRejectedResponse("invalid_request", "保存请求不是合法对象。", 0L, List.of()));
			}
			JsonObject rootObject = rootElement.getAsJsonObject();
			String draftId = readString(rootObject, "draftId", "draft");
			String baseSnapshotId = readString(rootObject, "baseSnapshotId", "graph");
			String mode = readString(rootObject, "mode", MODE_SERIAL);
			long baseGraphRevision = readLong(rootObject, "baseGraphRevision");
			JsonArray operationsArray = rootObject.has("operations") && rootObject.get("operations").isJsonArray()
				? rootObject.getAsJsonArray("operations")
				: new JsonArray();
			List<GraphWriteOperation> operations = new ArrayList<>(operationsArray.size());
			for (JsonElement operationElement : operationsArray) {
				if (!operationElement.isJsonObject()) {
					return ParseResult.failure(buildRejectedResponse("invalid_request", "保存请求中存在非法操作项。", 0L, List.of()));
				}
				JsonObject operationObject = operationElement.getAsJsonObject();
				String type = readString(operationObject, "type", "");
				switch (type) {
					case TYPE_RENAME_NODE_ALIAS -> operations.add(parseRenameNodeAlias(operationObject));
					case TYPE_REPLACE_TRIGGER_SOURCE_TARGETS -> operations.add(parseReplaceTriggerSourceTargets(operationObject));
					default -> {
						return ParseResult.failure(
							buildRejectedResponse(
								"unsupported_operation",
								"保存请求包含不支持的操作类型：%s。".formatted(type.isBlank() ? "-" : type),
								0L,
								List.of()
							)
						);
					}
				}
			}
			return ParseResult.success(new GraphWriteRequest(draftId, baseSnapshotId, mode, baseGraphRevision, operations));
		} catch (RuntimeException exception) {
			return ParseResult.failure(buildRejectedResponse("invalid_request", "保存请求解析失败。", 0L, List.of()));
		}
	}

	/**
	 * 构建成功应用的返回体。
	 */
	public static String buildAppliedResponse(
		String message,
		long graphRevision,
		List<UpdatedNodeState> updatedNodes
	) {
		return buildResultResponse("applied", "applied", message, graphRevision, updatedNodes);
	}

	/**
	 * 构建冲突返回体。
	 */
	public static String buildConflictResponse(
		String reason,
		String message,
		long graphRevision,
		List<UpdatedNodeState> updatedNodes
	) {
		return buildResultResponse("conflict", normalizeText(reason, "conflict"), message, graphRevision, updatedNodes);
	}

	/**
	 * 构建拒绝返回体。
	 */
	public static String buildRejectedResponse(
		String reason,
		String message,
		long graphRevision,
		List<UpdatedNodeState> updatedNodes
	) {
		return buildResultResponse("rejected", normalizeText(reason, "rejected"), message, graphRevision, updatedNodes);
	}

	private static String buildResultResponse(
		String result,
		String reason,
		String message,
		long graphRevision,
		List<UpdatedNodeState> updatedNodes
	) {
		StringBuilder builder = new StringBuilder(1024);
		builder.append('{');
		appendQuotedField(builder, "status", "ok");
		builder.append(',');
		appendQuotedField(builder, "result", normalizeText(result, "rejected"));
		builder.append(',');
		appendQuotedField(builder, "reason", normalizeText(reason, "rejected"));
		builder.append(',');
		appendQuotedField(builder, "message", normalizeText(message, ""));
		builder.append(',');
		appendNumberField(builder, "graphRevision", Math.max(0L, graphRevision));
		builder.append(',');
		appendUpdatedNodes(builder, updatedNodes);
		builder.append('}');
		return builder.toString();
	}

	private static void appendUpdatedNodes(StringBuilder builder, List<UpdatedNodeState> updatedNodes) {
		builder.append("\"updatedNodes\":[");
		List<UpdatedNodeState> values = updatedNodes == null ? List.of() : List.copyOf(updatedNodes);
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			UpdatedNodeState nodeState = values.get(index);
			builder.append('{');
			appendQuotedField(builder, "nodeKey", nodeState.nodeKey());
			builder.append(',');
			appendQuotedField(builder, "nodeType", LinkNodeSemantics.toSemanticName(nodeState.nodeType()));
			builder.append(',');
			appendNumberField(builder, "serial", nodeState.serial());
			builder.append(',');
			appendQuotedField(builder, "alias", nodeState.alias());
			builder.append(',');
			appendQuotedField(builder, "displayText", nodeState.displayText());
			builder.append(',');
			appendNumberField(builder, "sourceRevision", nodeState.sourceRevision());
			builder.append(',');
			appendNumberField(builder, "coreRevision", nodeState.coreRevision());
			builder.append('}');
		}
		builder.append(']');
	}

	private static RenameNodeAliasOperation parseRenameNodeAlias(JsonObject operationObject) {
		LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(readString(operationObject, "nodeType", "")).orElse(null);
		long serial = readLong(operationObject, "serial");
		String alias = readNullableString(operationObject, "alias");
		return new RenameNodeAliasOperation(nodeType, serial, alias);
	}

	private static ReplaceTriggerSourceTargetsOperation parseReplaceTriggerSourceTargets(JsonObject operationObject) {
		long triggerSourceSerial = readLong(operationObject, "triggerSourceSerial");
		long expectedSourceRevision = readLong(operationObject, "expectedSourceRevision");
		JsonArray targetSerialArray = operationObject.has("targetCoreSerials") && operationObject.get("targetCoreSerials").isJsonArray()
			? operationObject.getAsJsonArray("targetCoreSerials")
			: new JsonArray();
		List<Long> targetCoreSerials = new ArrayList<>(targetSerialArray.size());
		for (JsonElement targetSerialElement : targetSerialArray) {
			if (!targetSerialElement.isJsonPrimitive() || !targetSerialElement.getAsJsonPrimitive().isNumber()) {
				continue;
			}
			targetCoreSerials.add(Math.max(0L, targetSerialElement.getAsLong()));
		}
		return new ReplaceTriggerSourceTargetsOperation(triggerSourceSerial, expectedSourceRevision, targetCoreSerials);
	}

	private static String readString(JsonObject object, String memberName, String fallback) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return fallback;
		}
		JsonElement element = object.get(memberName);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return fallback;
		}
		return normalizeText(element.getAsString(), fallback);
	}

	private static String readNullableString(JsonObject object, String memberName) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return "";
		}
		JsonElement element = object.get(memberName);
		if (element == null || element.isJsonNull()) {
			return "";
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return "";
		}
		String rawText = element.getAsString();
		return rawText == null ? "" : rawText.trim();
	}

	private static long readLong(JsonObject object, String memberName) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return 0L;
		}
		JsonElement element = object.get(memberName);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return 0L;
		}
		return Math.max(0L, element.getAsLong());
	}

	private static String normalizeText(String rawText, String fallback) {
		if (rawText == null) {
			return fallback;
		}
		String normalized = rawText.trim();
		return normalized.isEmpty() ? fallback : normalized;
	}

	private static void appendQuotedField(StringBuilder builder, String fieldName, String fieldValue) {
		builder.append('"').append(fieldName).append("\":");
		appendQuoted(builder, fieldValue);
	}

	private static void appendNumberField(StringBuilder builder, String fieldName, long fieldValue) {
		builder.append('"').append(fieldName).append("\":").append(Math.max(0L, fieldValue));
	}

	private static void appendQuoted(StringBuilder builder, String rawValue) {
		builder.append('"').append(StatePanelRecordingJsonSupport.escapeJson(rawValue)).append('"');
	}

	/**
	 * 将更新后的节点状态列表按 `nodeKey` 稳定去重，供返回体直接使用。
	 */
	public static List<UpdatedNodeState> dedupeUpdatedNodes(List<UpdatedNodeState> updatedNodes) {
		if (updatedNodes == null || updatedNodes.isEmpty()) {
			return List.of();
		}
		Map<String, UpdatedNodeState> valuesByNodeKey = new LinkedHashMap<>();
		for (UpdatedNodeState updatedNodeState : updatedNodes) {
			if (updatedNodeState == null || updatedNodeState.nodeKey().isBlank()) {
				continue;
			}
			valuesByNodeKey.put(updatedNodeState.nodeKey(), updatedNodeState);
		}
		return List.copyOf(valuesByNodeKey.values());
	}

	/**
	 * graph 保存请求解析结果。
	 */
	public record ParseResult(GraphWriteRequest request, String failureResponseJson) {
		public static ParseResult success(GraphWriteRequest request) {
			return new ParseResult(request, "");
		}

		public static ParseResult failure(String failureResponseJson) {
			return new ParseResult(null, failureResponseJson == null ? "" : failureResponseJson);
		}

		public boolean successful() {
			return request != null;
		}
	}

	/**
	 * graph 保存请求。
	 */
	public record GraphWriteRequest(
		String draftId,
		String baseSnapshotId,
		String mode,
		long baseGraphRevision,
		List<GraphWriteOperation> operations
	) {
		public GraphWriteRequest {
			draftId = normalizeText(draftId, "draft");
			baseSnapshotId = normalizeText(baseSnapshotId, "graph");
			mode = normalizeText(mode, MODE_SERIAL).toLowerCase(Locale.ROOT);
			baseGraphRevision = Math.max(0L, baseGraphRevision);
			operations = List.copyOf(operations == null ? List.of() : operations);
		}
	}

	/**
	 * graph 保存结构化操作。
	 */
	public sealed interface GraphWriteOperation permits RenameNodeAliasOperation, ReplaceTriggerSourceTargetsOperation {
		String type();
	}

	/**
	 * 节点别名修改操作。
	 */
	public record RenameNodeAliasOperation(
		LinkNodeType nodeType,
		long serial,
		String alias
	) implements GraphWriteOperation {
		@Override
		public String type() {
			return TYPE_RENAME_NODE_ALIAS;
		}

		public RenameNodeAliasOperation {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE
				? LinkNodeType.TRIGGER_SOURCE
				: nodeType == LinkNodeType.CORE ? LinkNodeType.CORE : null;
			serial = Math.max(0L, serial);
			alias = alias == null ? "" : alias.trim();
		}

		public String nodeKey() {
			return LinkNodeSemantics.toSemanticName(nodeType) + ":" + serial;
		}
	}

	/**
	 * `triggerSource -> core` 目标集合覆盖操作。
	 */
	public record ReplaceTriggerSourceTargetsOperation(
		long triggerSourceSerial,
		long expectedSourceRevision,
		List<Long> targetCoreSerials
	) implements GraphWriteOperation {
		@Override
		public String type() {
			return TYPE_REPLACE_TRIGGER_SOURCE_TARGETS;
		}

		public ReplaceTriggerSourceTargetsOperation {
			triggerSourceSerial = Math.max(0L, triggerSourceSerial);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
			targetCoreSerials = normalizeTargetCoreSerials(targetCoreSerials);
		}

		private static List<Long> normalizeTargetCoreSerials(List<Long> targetCoreSerials) {
			if (targetCoreSerials == null || targetCoreSerials.isEmpty()) {
				return List.of();
			}
			Map<Long, Long> values = new LinkedHashMap<>();
			for (Long serialValue : targetCoreSerials) {
				long serial = serialValue == null ? 0L : serialValue;
				if (serial > 0L) {
					values.put(serial, serial);
				}
			}
			return List.copyOf(values.values());
		}
	}

	/**
	 * 保存后需要返回给网页端的节点最新状态。
	 */
	public record UpdatedNodeState(
		String nodeKey,
		LinkNodeType nodeType,
		long serial,
		String alias,
		String displayText,
		long sourceRevision,
		long coreRevision
	) {
		public UpdatedNodeState {
			nodeKey = normalizeText(nodeKey, "core:0");
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			alias = alias == null ? "" : alias.trim();
			displayText = normalizeText(displayText, NodeAliasDisplayUtil.formatDisplayText(alias, serial));
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
		}
	}
}
