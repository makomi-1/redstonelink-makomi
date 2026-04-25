package com.makomi.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 遥控器物品 tooltip 文案契约测试。
 */
@Tag("stable-core")
class LinkerItemTest {
	/**
	 * 遥控器跨区块 tooltip 应映射到“仅强加载、不支持常驻”文案键。
	 */
	@Test
	void crossChunkCapabilityTooltipShouldUseSharedTranslationKey() {
		TranslatableContents tooltip = requireTranslatable(LinkerItem.crossChunkCapabilityTooltip());

		assertEquals("tooltip.redstonelink.linker.crosschunk.force_load_only", tooltip.getKey());
	}

	private static TranslatableContents requireTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents);
		return (TranslatableContents) component.getContents();
	}
}
