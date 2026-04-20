package com.makomi.client.web;

import com.makomi.RedstoneLink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地网页偏好存储。
 * <p>
 * 负责把网页语言与主题持久化到客户端本地文件，避免随机端口导致的浏览器 origin 漂移让
 * `localStorage` 失效。
 * </p>
 */
public final class LocalWebPreferencesStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/client-web-preferences");
	private static final String PREFERENCES_FILE_NAME = "preferences.json";
	private final Path preferencesFilePath;
	private volatile LocalWebPreferences cachedPreferences = LocalWebPreferences.defaults();
	private volatile boolean initialized;

	public LocalWebPreferencesStore(Path preferencesFilePath) {
		this.preferencesFilePath = preferencesFilePath.toAbsolutePath().normalize();
	}

	/**
	 * @return 默认网页偏好存储，文件路径对齐 `gameDir/redstonelink/web/preferences.json`
	 */
	public static LocalWebPreferencesStore createDefault() {
		Path rootPath = LocalWebAssetRepository.createDefault().rootPath();
		return new LocalWebPreferencesStore(rootPath.resolve(PREFERENCES_FILE_NAME));
	}

	/**
	 * 读取当前网页偏好；若文件不存在或内容损坏，则回退到默认值。
	 */
	public synchronized LocalWebPreferences load() {
		if (initialized) {
			return cachedPreferences;
		}
		cachedPreferences = readFromDisk();
		initialized = true;
		return cachedPreferences;
	}

	/**
	 * 持久化网页偏好，并更新内存快照。
	 *
	 * @param preferences 待保存的网页偏好
	 * @return 已保存的规范化快照
	 * @throws IOException 写入失败时抛出
	 */
	public synchronized LocalWebPreferences save(LocalWebPreferences preferences) throws IOException {
		LocalWebPreferences normalizedPreferences = (preferences == null ? LocalWebPreferences.defaults() : preferences).normalized();
		writeTextAtomically(
			preferencesFilePath,
			LocalWebJsonSupport.buildPreferencesDocument(normalizedPreferences)
		);
		cachedPreferences = normalizedPreferences;
		initialized = true;
		return normalizedPreferences;
	}

	private LocalWebPreferences readFromDisk() {
		try {
			if (!Files.isRegularFile(preferencesFilePath)) {
				return LocalWebPreferences.defaults();
			}
			String rawJson = Files.readString(preferencesFilePath, StandardCharsets.UTF_8);
			return LocalWebJsonSupport.parsePreferencesDocument(rawJson);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("读取本地网页偏好失败，回退默认值: {}", preferencesFilePath, exception);
			return LocalWebPreferences.defaults();
		}
	}

	/**
	 * 原子写入小体积偏好文件，避免网页读取到半写状态。
	 */
	private static void writeTextAtomically(Path targetPath, String textContent) throws IOException {
		Path parentPath = targetPath.getParent();
		if (parentPath != null) {
			Files.createDirectories(parentPath);
		}
		Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
		Files.writeString(tempPath, textContent == null ? "" : textContent, StandardCharsets.UTF_8);
		try {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
