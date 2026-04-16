package com.makomi.client.web;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 本地网页资产类型。
 * <p>
 * 统一约定客户端本地缓存目录与文件扩展名，避免后续录制、图快照、草稿、布局各自发散。
 * </p>
 */
public enum LocalWebAssetKind {
	RECORDING("recording", "recordings", ".json.gz", true, "Recording Bundles"),
	GRAPH("graph", "graphs", ".json.gz", true, "Graph Snapshots"),
	DRAFT("draft", "drafts", ".json", false, "Graph Drafts"),
	LAYOUT("layout", "layouts", ".json", false, "Local Layouts");

	private final String token;
	private final String directoryName;
	private final String fileExtension;
	private final boolean compressed;
	private final String displayLabel;

	LocalWebAssetKind(String token, String directoryName, String fileExtension, boolean compressed, String displayLabel) {
		this.token = token;
		this.directoryName = directoryName;
		this.fileExtension = fileExtension;
		this.compressed = compressed;
		this.displayLabel = displayLabel;
	}

	/**
	 * @return 用于 HTTP 查询参数和 JSON 返回的稳定类型标识
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 客户端本地目录名称
	 */
	public String directoryName() {
		return directoryName;
	}

	/**
	 * @return 当前资产约定文件扩展名
	 */
	public String fileExtension() {
		return fileExtension;
	}

	/**
	 * @return 当前资产是否默认采用 gzip 压缩
	 */
	public boolean compressed() {
		return compressed;
	}

	/**
	 * @return 网页端展示标签
	 */
	public String displayLabel() {
		return displayLabel;
	}

	/**
	 * 解析当前资产类型的目录路径。
	 */
	public Path resolveDirectory(Path rootPath) {
		return rootPath.resolve(directoryName);
	}

	/**
	 * 解析查询参数中的资产类型。
	 */
	public static Optional<LocalWebAssetKind> tryParse(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return Optional.empty();
		}
		String normalizedValue = rawValue.trim().toLowerCase(Locale.ROOT);
		for (LocalWebAssetKind kind : values()) {
			if (kind.token.equals(normalizedValue) || kind.directoryName.equals(normalizedValue)) {
				return Optional.of(kind);
			}
		}
		return Optional.empty();
	}
}
