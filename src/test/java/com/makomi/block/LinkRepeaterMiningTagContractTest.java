package com.makomi.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器采掘标签契约测试。
 */
@Tag("stable-core")
class LinkRepeaterMiningTagContractTest {

	/**
	 * 转发器应纳入 pickaxe 采掘标签，避免生存模式下出现异常采掘体验。
	 */
	@Test
	void repeaterShouldBeIncludedInPickaxeMineableTag() throws Exception {
		Path pickaxeTagPath = Path.of("src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json");

		assertTrue(Files.exists(pickaxeTagPath), "缺少 pickaxe 采掘标签资源");
		String json = Files.readString(pickaxeTagPath, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"redstonelink:link_repeater\""), "转发器未纳入 pickaxe 采掘标签");
	}
}
