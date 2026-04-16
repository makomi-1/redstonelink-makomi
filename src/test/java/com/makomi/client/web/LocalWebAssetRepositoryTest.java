package com.makomi.client.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.client.web.LocalWebAssetRepository.StorageCategorySummary;
import com.makomi.client.web.LocalWebAssetRepository.StorageEntryContent;
import com.makomi.client.web.LocalWebAssetRepository.StorageIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地网页资产仓测试。
 */
@Tag("stable-core")
class LocalWebAssetRepositoryTest {
	@TempDir
	Path tempDirectory;

	/**
	 * 初始化时应创建四类目录，并在空仓场景下播种一组示例资产。
	 */
	@Test
	void ensureInitializedShouldCreateDirectoriesAndSeedSamples() throws IOException {
		LocalWebAssetRepository repository = new LocalWebAssetRepository(tempDirectory.resolve("redstonelink").resolve("web"));

		repository.ensureInitialized();
		StorageIndex storageIndex = repository.index();

		assertTrue(Files.isDirectory(repository.rootPath()));
		assertEquals(4, storageIndex.categories().size());
		for (LocalWebAssetKind assetKind : LocalWebAssetKind.values()) {
			assertTrue(Files.isDirectory(assetKind.resolveDirectory(repository.rootPath())));
			StorageCategorySummary categorySummary = findCategory(storageIndex, assetKind);
			assertEquals(1, categorySummary.entryCount());
			assertFalse(categorySummary.entries().isEmpty());
		}
	}

	/**
	 * 资产仓应同时支持读取 gzip JSON 和普通 JSON。
	 */
	@Test
	void readEntryShouldReturnReadableTextForCompressedAndPlainAssets() throws IOException {
		LocalWebAssetRepository repository = new LocalWebAssetRepository(tempDirectory.resolve("redstonelink").resolve("web"));
		repository.ensureInitialized();
		StorageIndex storageIndex = repository.index();

		String recordingFileName = findCategory(storageIndex, LocalWebAssetKind.RECORDING).entries().getFirst().fileName();
		StorageEntryContent recordingEntry = repository.readEntry(LocalWebAssetKind.RECORDING, recordingFileName);
		assertNotNull(recordingEntry);
		assertTrue(recordingEntry.compressed());
		assertTrue(recordingEntry.textContent().contains("\"kind\": \"recordingBundle\""));
		assertTrue(recordingEntry.textContent().contains("\"type\": \"triggerSource\""));

		String draftFileName = findCategory(storageIndex, LocalWebAssetKind.DRAFT).entries().getFirst().fileName();
		StorageEntryContent draftEntry = repository.readEntry(LocalWebAssetKind.DRAFT, draftFileName);
		assertNotNull(draftEntry);
		assertFalse(draftEntry.compressed());
		assertTrue(draftEntry.textContent().contains("\"kind\": \"graphDraft\""));
		assertTrue(draftEntry.textContent().contains("\"dirty\": true"));
	}

	/**
	 * 非法文件名必须被拒绝，避免目录穿越。
	 */
	@Test
	void readEntryShouldRejectUnsafeFileName() throws IOException {
		LocalWebAssetRepository repository = new LocalWebAssetRepository(tempDirectory.resolve("redstonelink").resolve("web"));
		repository.ensureInitialized();

		assertNull(repository.readEntry(LocalWebAssetKind.DRAFT, "../draft-sample.json"));
		assertFalse(LocalWebAssetRepository.isSafeAssetFileName("..\\draft.json"));
		assertFalse(LocalWebAssetRepository.isSafeAssetFileName("C:/temp/draft.json"));
		assertTrue(LocalWebAssetRepository.isSafeAssetFileName("draft-sample.json"));
	}

	/**
	 * 真实业务导出应可直接写入 recording gzip 资产。
	 */
	@Test
	void writeAssetBytesShouldPersistCompressedRecordingBundle() throws IOException {
		LocalWebAssetRepository repository = new LocalWebAssetRepository(tempDirectory.resolve("redstonelink").resolve("web"));
		repository.ensureInitialized();

		String fileName = "recording-test.json.gz";
		byte[] gzipBytes = new byte[] { 0x1F, (byte) 0x8B, 0x08, 0x00 };
		repository.writeAssetBytes(LocalWebAssetKind.RECORDING, fileName, gzipBytes);

		Path persistedPath = LocalWebAssetKind.RECORDING.resolveDirectory(repository.rootPath()).resolve(fileName);
		assertTrue(Files.isRegularFile(persistedPath));
		assertArrayEquals(gzipBytes, Files.readAllBytes(persistedPath));
	}

	private static StorageCategorySummary findCategory(StorageIndex storageIndex, LocalWebAssetKind assetKind) {
		return storageIndex.categories().stream().filter(category -> category.assetKind() == assetKind).findFirst().orElseThrow();
	}
}
