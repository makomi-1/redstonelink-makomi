package com.makomi.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 已放置节点 GUI 打开入口接线契约测试。
 */
@Tag("stable-core")
class PlacedPairableNodeGuiOpenContractTest {
	private static final String SHARED_HELPER_CALL = "PlacedPairableNodeGuiOpenSupport.ensureSerialReadyForPairingOpen";

	/**
	 * 所有已放置节点的配对 GUI 打开入口都应统一接入共享 serial 自愈逻辑。
	 */
	@Test
	void placedPairingGuiEntrancesShouldUseSharedSerialSupport() throws IOException {
		for (Path sourcePath : List.of(
			Path.of("src/main/java/com/makomi/block/LinkCoreBlock.java"),
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			Path.of("src/main/java/com/makomi/block/LinkButtonBlock.java"),
			Path.of("src/main/java/com/makomi/block/LinkSignalEmitterBlock.java"),
			Path.of("src/main/java/com/makomi/block/LinkSyncLeverBlock.java")
		)) {
			String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
			assertTrue(
				source.contains(SHARED_HELPER_CALL),
				() -> sourcePath + " 的已放置节点 GUI 打开入口未接入共享 serial 自愈逻辑"
			);
		}
	}
}
