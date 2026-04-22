package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ChunkActivatorItemData 稳定契约测试。
 */
@Tag("stable-core")
class ChunkActivatorItemDataTest {
	/**
	 * 物品 NBT 应保留当前作用类型与 `triggerSource/core` 两套配置。
	 */
	@Test
	void readWriteShouldRoundTripDualConfigsAndAlias() {
		ItemStack stack = new ItemStack(Items.STONE);
		ChunkActivatorConfigStateSnapshot snapshot = new ChunkActivatorConfigStateSnapshot(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("1/2", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("7/8", ChunkActivatorMode.RESIDENT)
		);

		ChunkActivatorItemData.write(stack, snapshot);
		ChunkActivatorItemData.setDisplayAlias(stack, "bench-core");

		assertEquals(snapshot, ChunkActivatorItemData.read(stack));
		assertEquals("bench-core", ChunkActivatorItemData.getDisplayAlias(stack));
	}
}
