package com.makomi.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.ChunkActivatorMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 区块激活器物品 tooltip 文案契约测试。
 */
@Tag("stable-core")
class ChunkActivatorBlockItemTest {
	/**
	 * force_load 与 resident 模式都应映射到独立的说明文案键。
	 */
	@Test
	void modeDetailTooltipShouldUseModeSpecificTranslationKeys() {
		TranslatableContents forceLoad = requireTranslatable(ChunkActivatorBlockItem.modeDetailTooltip(ChunkActivatorMode.FORCE_LOAD));
		TranslatableContents resident = requireTranslatable(ChunkActivatorBlockItem.modeDetailTooltip(ChunkActivatorMode.RESIDENT));

		assertEquals("tooltip.redstonelink.chunk_activator.mode_detail.force_load", forceLoad.getKey());
		assertEquals("tooltip.redstonelink.chunk_activator.mode_detail.resident", resident.getKey());
	}

	private static TranslatableContents requireTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents);
		return (TranslatableContents) component.getContents();
	}
}
