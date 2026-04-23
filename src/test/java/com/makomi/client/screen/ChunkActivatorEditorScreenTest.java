package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.ChunkActivatorMode;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 区块激活器编辑界面 tooltip 契约测试。
 */
@Tag("client")
class ChunkActivatorEditorScreenTest {
	/**
	 * 模式按钮 tooltip 应分别映射到强加载/常驻各自的文案键。
	 */
	@Test
	void buildModeTooltipLinesShouldUseModeSpecificTranslationKeys() {
		List<Component> forceLoad = ChunkActivatorEditorScreen.buildModeTooltipLines(ChunkActivatorMode.FORCE_LOAD);
		List<Component> resident = ChunkActivatorEditorScreen.buildModeTooltipLines(ChunkActivatorMode.RESIDENT);

		assertEquals(1, forceLoad.size());
		assertEquals(1, resident.size());
		assertEquals("screen.redstonelink.chunk_activator.mode.tooltip.force_load", requireTranslatable(forceLoad.get(0)).getKey());
		assertEquals("screen.redstonelink.chunk_activator.mode.tooltip.resident", requireTranslatable(resident.get(0)).getKey());
	}

	private static TranslatableContents requireTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents);
		return (TranslatableContents) component.getContents();
	}
}
