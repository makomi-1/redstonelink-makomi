package com.makomi.client.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地网页偏好存储测试。
 */
@Tag("stable-core")
class LocalWebPreferencesStoreTest {
	@TempDir
	Path tempDirectory;

	/**
	 * 持久化后的偏好应可从同一路径重新加载，确保跨会话恢复稳定。
	 */
	@Test
	void saveAndLoadShouldPersistPreferences() throws IOException {
		Path preferencesPath = tempDirectory.resolve("redstonelink").resolve("web").resolve("preferences.json");
		LocalWebPreferencesStore store = new LocalWebPreferencesStore(preferencesPath);

		store.save(new LocalWebPreferences("en-US", "lab-minimal"));
		LocalWebPreferencesStore reloadedStore = new LocalWebPreferencesStore(preferencesPath);
		LocalWebPreferences preferences = reloadedStore.load();

		assertEquals("en-US", preferences.language());
		assertEquals("lab-minimal", preferences.themeId());
	}

	/**
	 * 偏好文件损坏时应回退默认值，避免网页启动失败。
	 */
	@Test
	void loadShouldFallbackToDefaultsWhenDocumentIsInvalid() throws IOException {
		Path preferencesPath = tempDirectory.resolve("redstonelink").resolve("web").resolve("preferences.json");
		Files.createDirectories(preferencesPath.getParent());
		Files.writeString(preferencesPath, "{invalid-json", StandardCharsets.UTF_8);

		LocalWebPreferencesStore store = new LocalWebPreferencesStore(preferencesPath);
		LocalWebPreferences preferences = store.load();

		assertEquals(LocalWebPreferences.DEFAULT_LANGUAGE, preferences.language());
		assertEquals(LocalWebPreferences.DEFAULT_THEME_ID, preferences.themeId());
	}
}
