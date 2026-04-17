package com.makomi.client.web;

import com.makomi.client.web.LocalWebAssetRepository.StorageCategorySummary;
import com.makomi.client.web.LocalWebAssetRepository.StorageEntryContent;
import com.makomi.client.web.LocalWebAssetRepository.StorageEntrySummary;
import com.makomi.client.web.LocalWebAssetRepository.StorageIndex;

/**
 * 本地网页 Bridge JSON 序列化支持。
 * <p>
 * 当前阶段只覆盖桥接服务内部需要的几个只读 DTO，避免为 `P1` 单独引入额外 JSON 依赖。
 * </p>
 */
public final class LocalWebJsonSupport {
	private LocalWebJsonSupport() {
	}

	/**
	 * 构建 `/api/ping` 返回体。
	 */
	public static String buildPingPayload(
		String modId,
		String bridgeVersion,
		long startedAtEpochMillis,
		String baseUrl
	) {
		return """
			{
			  "status": "ok",
			  "modId": "%s",
			  "bridgeVersion": "%s",
			  "mode": "offline-embedded",
			  "startedAtEpochMillis": %d,
			  "baseUrl": "%s"
			}
			""".formatted(
			escapeJson(modId),
			escapeJson(bridgeVersion),
			startedAtEpochMillis,
			escapeJson(baseUrl)
		);
	}

	/**
	 * 构建资产索引 JSON。
	 */
	public static String buildStorageIndexPayload(StorageIndex storageIndex) {
		StringBuilder builder = new StringBuilder(4096);
		builder.append("{\"status\":\"ok\"");
		builder.append(",\"rootPath\":");
		appendQuoted(builder, storageIndex.rootPath().toString().replace('\\', '/'));
		builder.append(",\"refreshedAtEpochMillis\":").append(storageIndex.refreshedAtEpochMillis());
		builder.append(",\"categories\":[");
		for (int index = 0; index < storageIndex.categories().size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			appendCategorySummary(builder, storageIndex.categories().get(index));
		}
		builder.append("]}");
		return builder.toString();
	}

	/**
	 * 构建单条资产内容 JSON。
	 */
	public static String buildStorageEntryPayload(StorageEntryContent entryContent) {
		StringBuilder builder = new StringBuilder(4096);
		builder.append("{\"status\":\"ok\"");
		builder.append(",\"kind\":");
		appendQuoted(builder, entryContent.assetKind().token());
		builder.append(",\"label\":");
		appendQuoted(builder, entryContent.assetKind().displayLabel());
		builder.append(",\"fileName\":");
		appendQuoted(builder, entryContent.fileName());
		builder.append(",\"relativePath\":");
		appendQuoted(builder, entryContent.relativePath());
		builder.append(",\"compressed\":").append(entryContent.compressed());
		builder.append(",\"contentEncoding\":");
		appendQuoted(builder, entryContent.contentEncoding());
		builder.append(",\"sizeBytes\":").append(entryContent.sizeBytes());
		builder.append(",\"lastModifiedEpochMillis\":").append(entryContent.lastModifiedEpochMillis());
		builder.append(",\"textContent\":");
		appendQuoted(builder, entryContent.textContent());
		builder.append('}');
		return builder.toString();
	}

	/**
	 * 构建错误返回体。
	 */
	public static String buildErrorPayload(String message) {
		StringBuilder builder = new StringBuilder(256);
		builder.append("{\"status\":\"error\",\"message\":");
		appendQuoted(builder, message == null ? "" : message);
		builder.append('}');
		return builder.toString();
	}

	/**
	 * 构建最小成功返回体。
	 */
	public static String buildOkPayload() {
		return "{\"status\":\"ok\"}";
	}

	private static void appendCategorySummary(StringBuilder builder, StorageCategorySummary categorySummary) {
		builder.append('{');
		builder.append("\"kind\":");
		appendQuoted(builder, categorySummary.assetKind().token());
		builder.append(",\"label\":");
		appendQuoted(builder, categorySummary.displayLabel());
		builder.append(",\"directoryName\":");
		appendQuoted(builder, categorySummary.directoryName());
		builder.append(",\"fileExtension\":");
		appendQuoted(builder, categorySummary.fileExtension());
		builder.append(",\"compressed\":").append(categorySummary.assetKind().compressed());
		builder.append(",\"entryCount\":").append(categorySummary.entryCount());
		builder.append(",\"entries\":[");
		for (int index = 0; index < categorySummary.entries().size(); index++) {
			if (index > 0) {
				builder.append(',');
			}
			appendEntrySummary(builder, categorySummary.entries().get(index));
		}
		builder.append("]}");
	}

	private static void appendEntrySummary(StringBuilder builder, StorageEntrySummary entrySummary) {
		builder.append('{');
		builder.append("\"kind\":");
		appendQuoted(builder, entrySummary.assetKind().token());
		builder.append(",\"fileName\":");
		appendQuoted(builder, entrySummary.fileName());
		builder.append(",\"relativePath\":");
		appendQuoted(builder, entrySummary.relativePath());
		builder.append(",\"compressed\":").append(entrySummary.compressed());
		builder.append(",\"sizeBytes\":").append(entrySummary.sizeBytes());
		builder.append(",\"lastModifiedEpochMillis\":").append(entrySummary.lastModifiedEpochMillis());
		builder.append('}');
	}

	private static void appendQuoted(StringBuilder builder, String rawValue) {
		builder.append('"').append(escapeJson(rawValue)).append('"');
	}

	/**
	 * 最小 JSON 字符串转义，覆盖当前 Bridge 返回体中可能出现的控制字符。
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
						builder.append(String.format("\\u%04x", (int) currentChar));
					} else {
						builder.append(currentChar);
					}
				}
			}
		}
		return builder.toString();
	}
}
