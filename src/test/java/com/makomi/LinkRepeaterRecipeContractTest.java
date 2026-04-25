package com.makomi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器配方资源契约测试。
 */
@Tag("stable-core")
class LinkRepeaterRecipeContractTest {

	/**
	 * 转发器应具备正式运行时配方，且关键输入输出不得缺失。
	 */
	@Test
	void repeaterRecipeShouldExistWithExpectedIngredients() throws Exception {
		Path recipePath = Path.of("src/main/resources/data/redstonelink/recipe/link_repeater.json");

		assertTrue(Files.exists(recipePath), "转发器缺少运行时配方");
		String json = Files.readString(recipePath, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"minecraft:crafting_shaped\""), "转发器配方必须为 shaped recipe");
		assertTrue(json.contains("\"redstonelink:link_redstone_core\""), "转发器配方缺少连接核心块输入");
		assertTrue(json.contains("\"redstonelink:redstone_link_component\""), "转发器配方缺少连接原件输入");
		assertTrue(json.contains("\"redstonelink:link_sync_emitter\""), "转发器配方缺少同步发射器输入");
		assertTrue(json.contains("\"redstonelink:link_repeater\""), "转发器配方缺少正确输出");
	}
}
