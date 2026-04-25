package com.makomi.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器掉落资源契约测试。
 */
@Tag("stable-core")
class LinkRepeaterLootTableContractTest {

	/**
	 * 转发器应声明显式自掉落 loot table，避免破坏路径遗漏导致不掉落。
	 */
	@Test
	void repeaterLootTableShouldExistAndDropSelf() throws Exception {
		Path lootTablePath = Path.of("src/main/resources/data/redstonelink/loot_table/blocks/link_repeater.json");

		assertTrue(Files.exists(lootTablePath), "转发器缺少显式 loot table");
		String json = Files.readString(lootTablePath, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"redstonelink:link_repeater\""), "转发器 loot table 未声明自掉落物品");
	}
}
