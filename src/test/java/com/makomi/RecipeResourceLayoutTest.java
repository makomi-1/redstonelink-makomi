package com.makomi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 配方资源目录契约测试。
 * <p>
 * 防止运行时配方目录回退到错误的 `recipe/` 路径，或在迁移过程中丢失既有配方文件。
 * </p>
 */
@Tag("stable-core")
class RecipeResourceLayoutTest {
	private static final Path RECIPE_DIR = Path.of("src/main/resources/data/redstonelink/recipe");
	private static final Path NON_STANDARD_RECIPES_DIR = Path.of("src/main/resources/data/redstonelink/recipes");
	private static final Set<String> EXPECTED_RECIPE_FILES = Set.of(
		"directional_face_editor.json",
		"graph_visual_editor.json",
		"hide_chunk_activator.json",
		"hide_core.json",
		"hide_pulse_emitter.json",
		"hide_receive_filter.json",
		"hide_repeater.json",
		"hide_send_filter.json",
		"hide_sync_trigger_source.json",
		"hide_toggle_emitter.json",
		"link_chunk_activator.json",
		"link_pulse_emitter.json",
		"link_push_button.json",
		"link_receive_filter.json",
		"link_repeater.json",
		"link_redstone_core_from_transparent.json",
		"link_redstone_core_transparent.json",
		"link_redstone_core.json",
		"link_redstone_dust_core_from_transparent.json",
		"link_redstone_dust_core_transparent.json",
		"link_redstone_dust_core.json",
		"link_send_filter.json",
		"link_sync_emitter.json",
		"link_sync_lever.json",
		"link_toggle_button.json",
		"link_toggle_emitter.json",
		"quick_link_tool.json",
		"redstone_link_component.json",
		"redstonelink_pulse_linker.json",
		"redstonelink_status_panel.json",
		"redstonelink_sync_linker.json",
		"redstonelink_toggle_linker.json",
		"smart_glasses.json",
		"smart_node_container.json",
		"wireless_conversion_wireless_end_rod.json",
		"wireless_conversion_wireless_glowstone.json",
		"wireless_conversion_wireless_heavy_weighted_pressure_plate.json",
		"wireless_conversion_wireless_iron_door.json",
		"wireless_conversion_wireless_iron_trapdoor.json",
		"wireless_conversion_wireless_jack_o_lantern.json",
		"wireless_conversion_wireless_lantern.json",
		"wireless_conversion_wireless_lever.json",
		"wireless_conversion_wireless_light_weighted_pressure_plate.json",
		"wireless_conversion_wireless_note_block.json",
		"wireless_conversion_wireless_ochre_froglight.json",
		"wireless_conversion_wireless_oak_button.json",
		"wireless_conversion_wireless_oak_fence_gate.json",
		"wireless_conversion_wireless_pearlescent_froglight.json",
		"wireless_conversion_wireless_piston.json",
		"wireless_conversion_wireless_redstone_lamp.json",
		"wireless_conversion_wireless_redstone_block.json",
		"wireless_conversion_wireless_oak_door.json",
		"wireless_conversion_wireless_oak_pressure_plate.json",
		"wireless_conversion_wireless_oak_trapdoor.json",
		"wireless_conversion_wireless_sea_lantern.json",
		"wireless_conversion_wireless_soul_lantern.json",
		"wireless_conversion_wireless_sticky_piston.json",
		"wireless_conversion_wireless_stone_button.json",
		"wireless_conversion_wireless_stone_pressure_plate.json",
		"wireless_conversion_wireless_tnt.json",
		"wireless_conversion_wireless_verdant_froglight.json"
	);

	/**
	 * 运行时配方应全部落在标准 `recipe/` 目录下。
	 */
	@Test
	void runtimeRecipesShouldLiveUnderStandardRecipeDirectory() throws IOException {
		assertTrue(Files.isDirectory(RECIPE_DIR), "运行时 recipe 目录必须存在");

		Set<String> actualFiles = listJsonFileNames(RECIPE_DIR);

		assertEquals(EXPECTED_RECIPE_FILES, actualFiles, "运行时配方文件清单必须与约定的运行时配方集合一致");
	}

	/**
	 * 非标准 `recipes/` 目录不应再保留运行时 JSON 配方。
	 */
	@Test
	void nonStandardRecipesDirectoryShouldNotContainRuntimeJsonFiles() throws IOException {
		if (!Files.exists(NON_STANDARD_RECIPES_DIR)) {
			return;
		}

		assertTrue(listJsonFileNames(NON_STANDARD_RECIPES_DIR).isEmpty(), "非标准 recipes 目录不应再保留运行时 JSON 配方");
	}

	/**
	 * 收集目录下的 JSON 文件名。
	 */
	private static Set<String> listJsonFileNames(Path directory) throws IOException {
		try (Stream<Path> paths = Files.list(directory)) {
			return paths
				.filter(Files::isRegularFile)
				.map(Path::getFileName)
				.map(Path::toString)
				.filter(name -> name.endsWith(".json"))
				.collect(Collectors.toCollection(TreeSet::new));
		}
	}
}
