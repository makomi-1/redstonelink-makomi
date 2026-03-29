package com.makomi.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkItemData;
import java.util.List;
import java.util.stream.LongStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 聚合点击 helper 的上限行为测试。
 */
@Tag("stable-core")
class PairableItemAggregateClickSupportTest {

	/**
	 * 初始化 Minecraft 基础注册表，确保 Item/ItemStack 在单测环境可用。
	 */
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 左键整组合并时，目标聚合数不应超过 64，溢出部分留在来源栈。
	 */
	@Test
	void primaryMergeShouldRespectAggregateStackLimit() {
		SimpleContainer container = new SimpleContainer(1);
		Slot slot = new Slot(container, 0, 0, 0);
		ItemStack slotStack = new ItemStack(Items.STONE);
		ItemStack carriedStack = new ItemStack(Items.STONE);
		LinkItemData.setSerialGroup(slotStack, serialRange(1L, 63L));
		LinkItemData.setSerialGroup(carriedStack, List.of(64L, 65L, 66L));
		container.setItem(0, slotStack);

		assertTrue(PairableItemAggregateClickSupport.overrideStackedOnOther(carriedStack, slot, ClickAction.PRIMARY, null));
		assertEquals(serialRange(1L, 64L), LinkItemData.getSerialGroup(slot.getItem()));
		assertEquals(List.of(65L, 66L), LinkItemData.getSerialGroup(carriedStack));
		assertEquals(LinkItemData.AGGREGATE_STACK_LIMIT, LinkItemData.getSerialCount(slot.getItem()));
	}

	/**
	 * 目标聚合栈已满时，右键并入一个应直接拒绝，不得改动任一侧。
	 */
	@Test
	void secondaryPlaceOneShouldRejectWhenTargetAlreadyFull() {
		SimpleContainer container = new SimpleContainer(1);
		Slot slot = new Slot(container, 0, 0, 0);
		ItemStack slotStack = new ItemStack(Items.STONE);
		ItemStack carriedStack = new ItemStack(Items.STONE);
		List<Long> fullSerials = serialRange(1L, LinkItemData.AGGREGATE_STACK_LIMIT);
		LinkItemData.setSerialGroup(slotStack, fullSerials);
		LinkItemData.setSerialGroup(carriedStack, List.of(100L, 101L));
		container.setItem(0, slotStack);

		assertFalse(PairableItemAggregateClickSupport.overrideStackedOnOther(carriedStack, slot, ClickAction.SECONDARY, null));
		assertEquals(fullSerials, LinkItemData.getSerialGroup(slot.getItem()));
		assertEquals(List.of(100L, 101L), LinkItemData.getSerialGroup(carriedStack));
	}

	/**
	 * 构造闭区间有序序号列表，便于表达聚合态预期。
	 */
	private static List<Long> serialRange(long startInclusive, long endInclusive) {
		return LongStream.rangeClosed(startInclusive, endInclusive).boxed().toList();
	}
}
