package com.makomi.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * 智能节点容器物品的放置消费决策测试。
 */
class SmartNodeContainerItemTest {
	@Test
	void resolveConsumedSlotReplacementShouldClearSlotInCreativeInstabuild() {
		ItemStack nestedStack = new ItemStack(Items.STONE);

		ItemStack resolved = SmartNodeContainerItem.resolveConsumedSlotReplacement(nestedStack, true);

		assertTrue(resolved.isEmpty());
	}

	@Test
	void resolveConsumedSlotReplacementShouldKeepNestedStackResultInSurvival() {
		ItemStack nestedStack = new ItemStack(Items.STONE);

		ItemStack resolved = SmartNodeContainerItem.resolveConsumedSlotReplacement(nestedStack, false);

		assertFalse(resolved.isEmpty());
		assertTrue(resolved.is(Items.STONE));
	}
}
