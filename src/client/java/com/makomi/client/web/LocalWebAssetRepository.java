package com.makomi.client.web;

import com.makomi.RedstoneLink;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地网页资产仓。
 * <p>
 * 负责管理 `gameDir/redstonelink/web/**` 下的本地只读资产目录、占位示例资产和资产读取能力。
 * </p>
 */
public final class LocalWebAssetRepository {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/client-web-storage");
	private static final String ROOT_DIRECTORY_NAME = "redstonelink";
	private static final String WEB_DIRECTORY_NAME = "web";
	private static final String SAMPLE_MARKER_FILE_NAME = ".p1-sample-assets.seeded";
	private final Path rootPath;

	public LocalWebAssetRepository(Path rootPath) {
		this.rootPath = Objects.requireNonNull(rootPath, "rootPath").toAbsolutePath().normalize();
	}

	/**
	 * 创建默认资产仓，根目录对齐整体方案中的 `gameDir/redstonelink/web`。
	 */
	public static LocalWebAssetRepository createDefault() {
		return new LocalWebAssetRepository(resolveDefaultRootPath());
	}

	/**
	 * @return 本地资产根目录
	 */
	public Path rootPath() {
		return rootPath;
	}

	/**
	 * 初始化根目录和四类子目录，并在空仓场景下补一组示例资产。
	 */
	public void ensureInitialized() throws IOException {
		Files.createDirectories(rootPath);
		for (LocalWebAssetKind assetKind : LocalWebAssetKind.values()) {
			Files.createDirectories(assetKind.resolveDirectory(rootPath));
		}
		ensureSampleAssetsIfNeeded();
	}

	/**
	 * 构建当前资产仓索引快照。
	 */
	public StorageIndex index() throws IOException {
		ensureInitialized();
		List<StorageCategorySummary> categories = new ArrayList<>();
		for (LocalWebAssetKind assetKind : LocalWebAssetKind.values()) {
			List<StorageEntrySummary> entries = listEntries(assetKind);
			categories.add(
				new StorageCategorySummary(
					assetKind,
					assetKind.directoryName(),
					assetKind.displayLabel(),
					assetKind.fileExtension(),
					entries.size(),
					entries
				)
			);
		}
		return new StorageIndex(System.currentTimeMillis(), rootPath, List.copyOf(categories));
	}

	/**
	 * 读取指定资产条目内容；若路径非法或文件不存在则返回 `null`。
	 */
	public StorageEntryContent readEntry(LocalWebAssetKind assetKind, String fileName) throws IOException {
		Objects.requireNonNull(assetKind, "assetKind");
		ensureInitialized();
		if (!isSafeAssetFileName(fileName) || !fileName.endsWith(assetKind.fileExtension())) {
			return null;
		}
		Path assetDirectory = assetKind.resolveDirectory(rootPath).toAbsolutePath().normalize();
		Path assetPath = assetDirectory.resolve(fileName).toAbsolutePath().normalize();
		if (!assetPath.startsWith(assetDirectory) || !Files.isRegularFile(assetPath)) {
			return null;
		}
		BasicFileAttributes attributes = Files.readAttributes(assetPath, BasicFileAttributes.class);
		String textContent = assetKind.compressed() ? readCompressedText(assetPath) : Files.readString(assetPath, StandardCharsets.UTF_8);
		return new StorageEntryContent(
			assetKind,
			assetPath.getFileName().toString(),
			toRelativePath(assetPath),
			assetKind.compressed(),
			attributes.size(),
			attributes.lastModifiedTime().toMillis(),
			textContent
		);
	}

	/**
	 * 写入单条资产字节内容。
	 * <p>
	 * 该入口用于 `P2+` 的真实业务导出，例如 recording bundle，
	 * 统一复用现有目录初始化、文件名校验与原子写入能力。
	 * </p>
	 */
	public void writeAssetBytes(LocalWebAssetKind assetKind, String fileName, byte[] bytes) throws IOException {
		Objects.requireNonNull(assetKind, "assetKind");
		ensureInitialized();
		if (!isSafeAssetFileName(fileName) || !fileName.endsWith(assetKind.fileExtension())) {
			throw new IOException("unsafe asset file name: " + fileName);
		}
		Path assetDirectory = assetKind.resolveDirectory(rootPath).toAbsolutePath().normalize();
		Path assetPath = assetDirectory.resolve(fileName).toAbsolutePath().normalize();
		if (!assetPath.startsWith(assetDirectory)) {
			throw new IOException("asset path escaped target directory: " + fileName);
		}
		writeBytesAtomically(assetPath, bytes == null ? new byte[0] : bytes);
	}

	/**
	 * 写入单条 UTF-8 文本资产。
	 */
	public void writeAssetText(LocalWebAssetKind assetKind, String fileName, String textContent) throws IOException {
		Objects.requireNonNull(assetKind, "assetKind");
		ensureInitialized();
		if (!isSafeAssetFileName(fileName) || !fileName.endsWith(assetKind.fileExtension())) {
			throw new IOException("unsafe asset file name: " + fileName);
		}
		Path assetDirectory = assetKind.resolveDirectory(rootPath).toAbsolutePath().normalize();
		Path assetPath = assetDirectory.resolve(fileName).toAbsolutePath().normalize();
		if (!assetPath.startsWith(assetDirectory)) {
			throw new IOException("asset path escaped target directory: " + fileName);
		}
		if (assetKind.compressed()) {
			writeCompressedTextAtomically(assetPath, textContent == null ? "" : textContent);
			return;
		}
		writeTextAtomically(assetPath, textContent == null ? "" : textContent);
	}

	/**
	 * 删除单条本地资产。
	 */
	public void deleteAsset(LocalWebAssetKind assetKind, String fileName) throws IOException {
		Objects.requireNonNull(assetKind, "assetKind");
		ensureInitialized();
		if (!isSafeAssetFileName(fileName) || !fileName.endsWith(assetKind.fileExtension())) {
			throw new IOException("unsafe asset file name: " + fileName);
		}
		Path assetDirectory = assetKind.resolveDirectory(rootPath).toAbsolutePath().normalize();
		Path assetPath = assetDirectory.resolve(fileName).toAbsolutePath().normalize();
		if (!assetPath.startsWith(assetDirectory)) {
			throw new IOException("asset path escaped target directory: " + fileName);
		}
		Files.deleteIfExists(assetPath);
	}

	/**
	 * 校验资产文件名是否安全，阻断目录穿越和绝对路径。
	 */
	static boolean isSafeAssetFileName(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return false;
		}
		String normalizedFileName = fileName.trim();
		return !normalizedFileName.contains("..")
			&& !normalizedFileName.contains("/")
			&& !normalizedFileName.contains("\\")
			&& !normalizedFileName.contains(":");
	}

	private List<StorageEntrySummary> listEntries(LocalWebAssetKind assetKind) throws IOException {
		Path assetDirectory = assetKind.resolveDirectory(rootPath);
		if (!Files.exists(assetDirectory)) {
			return List.of();
		}
		try (Stream<Path> pathStream = Files.list(assetDirectory)) {
			return pathStream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(assetKind.fileExtension()))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.map(path -> toEntrySummary(assetKind, path))
				.toList();
		}
	}

	private StorageEntrySummary toEntrySummary(LocalWebAssetKind assetKind, Path assetPath) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(assetPath, BasicFileAttributes.class);
			return new StorageEntrySummary(
				assetKind,
				assetPath.getFileName().toString(),
				toRelativePath(assetPath),
				assetKind.compressed(),
				attributes.size(),
				attributes.lastModifiedTime().toMillis()
			);
		} catch (IOException exception) {
			LOGGER.warn("读取本地网页资产元数据失败: {}", assetPath, exception);
			return new StorageEntrySummary(assetKind, assetPath.getFileName().toString(), toRelativePath(assetPath), assetKind.compressed(), 0L, 0L);
		}
	}

	private String toRelativePath(Path assetPath) {
		return rootPath.relativize(assetPath).toString().replace('\\', '/');
	}

	/**
	 * 仅在空资产仓首次初始化时补一组占位示例资产，避免后续真实导出链路未接入前网页端完全无数据。
	 */
	private void ensureSampleAssetsIfNeeded() throws IOException {
		Path markerPath = rootPath.resolve(SAMPLE_MARKER_FILE_NAME);
		if (Files.exists(markerPath)) {
			return;
		}
		if (!hasAnyAssetFiles()) {
			writeSampleAssets();
		}
		Files.writeString(markerPath, "seeded=p1\n", StandardCharsets.UTF_8);
	}

	private boolean hasAnyAssetFiles() throws IOException {
		for (LocalWebAssetKind assetKind : LocalWebAssetKind.values()) {
			Path assetDirectory = assetKind.resolveDirectory(rootPath);
			if (!Files.exists(assetDirectory)) {
				continue;
			}
			try (Stream<Path> pathStream = Files.list(assetDirectory)) {
				if (pathStream.anyMatch(Files::isRegularFile)) {
					return true;
				}
			}
		}
		return false;
	}

	private void writeSampleAssets() throws IOException {
		writeAsset(LocalWebAssetKind.RECORDING, "recording-sample.json.gz", recordingSampleContent());
		writeAsset(LocalWebAssetKind.GRAPH, "graph-sample.json.gz", graphSampleContent());
		writeAsset(LocalWebAssetKind.DRAFT, "draft-sample.json", draftSampleContent());
		writeAsset(LocalWebAssetKind.LAYOUT, "layout-sample.json", layoutSampleContent());
	}

	private void writeAsset(LocalWebAssetKind assetKind, String fileName, String textContent) throws IOException {
		Path targetPath = assetKind.resolveDirectory(rootPath).resolve(fileName);
		if (assetKind.compressed()) {
			writeCompressedTextAtomically(targetPath, textContent);
			return;
		}
		writeTextAtomically(targetPath, textContent);
	}

	private static Path resolveDefaultRootPath() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			if (loader != null && loader.getGameDir() != null) {
				return loader.getGameDir().resolve(ROOT_DIRECTORY_NAME).resolve(WEB_DIRECTORY_NAME);
			}
		} catch (RuntimeException ignored) {
			// 测试环境回退到相对目录。
		}
		return Path.of("run").resolve(ROOT_DIRECTORY_NAME).resolve(WEB_DIRECTORY_NAME);
	}

	private static String readCompressedText(Path assetPath) throws IOException {
		byte[] compressedBytes = Files.readAllBytes(assetPath);
		try (
			ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedBytes);
			GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream)
		) {
			return new String(gzipInputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void writeCompressedTextAtomically(Path targetPath, String textContent) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
			gzipOutputStream.write(textContent.getBytes(StandardCharsets.UTF_8));
		}
		writeBytesAtomically(targetPath, byteArrayOutputStream.toByteArray());
	}

	private static void writeTextAtomically(Path targetPath, String textContent) throws IOException {
		writeBytesAtomically(targetPath, textContent.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 原子写入小体积资产文件，避免网页读取到半写状态。
	 */
	private static void writeBytesAtomically(Path targetPath, byte[] bytes) throws IOException {
		Path parentPath = targetPath.getParent();
		if (parentPath != null) {
			Files.createDirectories(parentPath);
		}
		Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
		Files.write(tempPath, bytes);
		try {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String recordingSampleContent() {
		return """
			{
			  "sample": true,
			  "kind": "recordingBundle",
			  "manifest": {
			    "recordingId": "recording-sample",
			    "title": "P1 Sample Recording",
			    "formatVersion": 1,
			    "sampleEveryTicks": 2,
			    "startedTick": 1200,
			    "endedTick": 1220,
			    "nodeCount": 2,
			    "sampleCount": 6
			  },
			  "nodes": [
			    {
			      "nodeKey": "triggerSource:12",
			      "type": "triggerSource",
			      "serial": 12,
			      "alias": "样例来源",
			      "displayText": "triggerSource 12"
			    },
			    {
			      "nodeKey": "core:88",
			      "type": "core",
			      "serial": 88,
			      "alias": "样例核心",
			      "displayText": "core 88"
			    }
			  ],
			  "series": [
			    {
			      "nodeKey": "core:88",
			      "samples": [
			        { "tick": 1200, "online": true, "active": false, "inputPower": 0, "outputPower": 0 },
			        { "tick": 1202, "online": true, "active": true, "inputPower": 12, "outputPower": 15 }
			      ]
			    }
			  ],
			  "markers": [
			    { "tick": 1202, "label": "sample-marker" }
			  ]
			}
			""";
	}

	private static String graphSampleContent() {
		return """
			{
			  "sample": true,
			  "kind": "graphSnapshotBundle",
			  "snapshotId": "graph-sample",
			  "mode": "serial",
			  "graphRevision": 3,
			  "generatedAtTick": 2048,
			  "viewerPlayerId": "sample-player",
			  "structureChecksum": "samplechecksum0001",
			  "nodes": [
			    {
			      "nodeKey": "triggerSource:12",
			      "type": "triggerSource",
			      "serial": 12,
			      "alias": "样例来源",
			      "displayText": "样例来源(#12)",
			      "allocated": true,
			      "retired": false,
			      "connectionMode": "serial",
			      "channel": 0,
			      "sourceRevision": 3,
			      "coreRevision": 0,
			      "capabilityFlags": ["outbound", "readonly"]
			    },
			    {
			      "nodeKey": "core:88",
			      "type": "core",
			      "serial": 88,
			      "alias": "样例核心",
			      "displayText": "样例核心(#88)",
			      "allocated": true,
			      "retired": false,
			      "connectionMode": "serial",
			      "channel": 0,
			      "sourceRevision": 0,
			      "coreRevision": 2,
			      "capabilityFlags": ["inbound", "readonly"]
			    }
			  ],
			  "edges": [
			    {
			      "edgeKey": "triggerSource:12->core:88",
			      "sourceNodeKey": "triggerSource:12",
			      "targetNodeKey": "core:88",
			      "kind": "serial",
			      "readable": true,
			      "editable": false
			    }
			  ],
			  "stats": {
			    "nodeCount": 2,
			    "edgeCount": 1,
			    "triggerSourceCount": 1,
			    "coreCount": 1,
			    "maskedSourceCount": 0
			  }
			}
			""";
	}

	private static String draftSampleContent() {
		return """
			{
			  "sample": true,
			  "kind": "graphDraft",
			  "draftId": "draft-sample",
			  "baseSnapshotId": "graph-sample",
			  "mode": "serial",
			  "baseGraphRevision": 3,
			  "dirty": true,
			  "layoutVersion": 1,
			  "operations": [
			    {
			      "type": "RenameNodeAlias",
			      "nodeKey": "core:88",
			      "alias": "新的样例核心"
			    }
			  ]
			}
			""";
	}

	private static String layoutSampleContent() {
		return """
			{
			  "sample": true,
			  "kind": "layout",
			  "layoutId": "layout-sample",
			  "snapshotId": "graph-sample",
			  "collapsedGroups": [],
			  "nodes": [
			    { "nodeKey": "triggerSource:12", "x": 160.0, "y": 140.0 },
			    { "nodeKey": "core:88", "x": 440.0, "y": 180.0 }
			  ]
			}
			""";
	}

	/**
	 * 资产仓索引快照。
	 */
	public record StorageIndex(long refreshedAtEpochMillis, Path rootPath, List<StorageCategorySummary> categories) {
		public StorageIndex {
			categories = List.copyOf(categories == null ? List.of() : categories);
		}
	}

	/**
	 * 单类资产目录摘要。
	 */
	public record StorageCategorySummary(
		LocalWebAssetKind assetKind,
		String directoryName,
		String displayLabel,
		String fileExtension,
		int entryCount,
		List<StorageEntrySummary> entries
	) {
		public StorageCategorySummary {
			entries = List.copyOf(entries == null ? List.of() : entries);
		}
	}

	/**
	 * 资产条目摘要。
	 */
	public record StorageEntrySummary(
		LocalWebAssetKind assetKind,
		String fileName,
		String relativePath,
		boolean compressed,
		long sizeBytes,
		long lastModifiedEpochMillis
	) {}

	/**
	 * 单条资产内容。
	 */
	public record StorageEntryContent(
		LocalWebAssetKind assetKind,
		String fileName,
		String relativePath,
		boolean compressed,
		long sizeBytes,
		long lastModifiedEpochMillis,
		String textContent
	) {
		public String contentEncoding() {
			return compressed ? "gzip+utf8" : "utf8";
		}
	}
}
