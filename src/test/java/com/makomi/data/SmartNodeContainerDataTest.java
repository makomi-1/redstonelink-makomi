package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import com.makomi.registry.ModItems;
import com.makomi.testsupport.TestMinecraftSupport;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 智能节点容器数据工具回归测试。
 */
@Tag("stable-core")
class SmartNodeContainerDataTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		TestMinecraftSupport.bootstrapMinecraft();
		TestMinecraftSupport.withWritableBlockRegistries(() -> {
			ModItems.register();
			return null;
		});
	}

	@Test
	void classifyShouldTreatHideRepeaterAsRepeater() {
		assertEquals(
			SmartNodeContainerPlacementType.REPEATER,
			SmartNodeContainerData.classify(new ItemStack(ModItems.HIDE_REPEATER))
		);
	}

	@Test
	void findSlotsForTypeShouldReturnVisibleAndHiddenRepeaterSlotsInCurrentOrder() {
		NonNullList<ItemStack> contents = NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY);
		contents.set(2, new ItemStack(ModItems.HIDE_REPEATER));
		contents.set(5, new ItemStack(ModItems.LINK_REPEATER));

		assertIterableEquals(
			java.util.List.of(2, 5),
			SmartNodeContainerData.findSlotsForType(contents, SmartNodeContainerPlacementType.REPEATER)
		);
	}

	@Test
	void findSlotsForTypeShouldReturnEmptyListForEmptyContents() {
		assertIterableEquals(
			java.util.List.of(),
			SmartNodeContainerData.findSlotsForType(NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY), SmartNodeContainerPlacementType.REPEATER)
		);
	}

	@Test
	void cycleTemporarySelectedSlotShouldWrapWithinCurrentPlacementType() {
		NonNullList<ItemStack> contents = NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY);
		contents.set(1, new ItemStack(ModItems.LINK_REDSTONE_CORE));
		contents.set(3, new ItemStack(ModItems.LINK_REDSTONE_CORE));
		contents.set(7, new ItemStack(ModItems.LINK_REDSTONE_CORE));

		assertEquals(
			1,
			SmartNodeContainerData.cycleTemporarySelectedSlot(contents, SmartNodeContainerPlacementType.CORE, -1, 1)
		);
		assertEquals(
			3,
			SmartNodeContainerData.cycleTemporarySelectedSlot(contents, SmartNodeContainerPlacementType.CORE, 1, 1)
		);
		assertEquals(
			7,
			SmartNodeContainerData.cycleTemporarySelectedSlot(contents, SmartNodeContainerPlacementType.CORE, 1, -1)
		);
	}

	@Test
	void cycleTemporarySelectedSlotShouldReturnNegativeOneForEmptyContents() {
		assertEquals(
			-1,
			SmartNodeContainerData.cycleTemporarySelectedSlot(
				NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY),
				SmartNodeContainerPlacementType.CORE,
				-1,
				1
			)
		);
	}

	@Test
	void resolvePreferredSlotForTypeShouldPreferTemporarySlotAndFallbackToFirstSlot() {
		NonNullList<ItemStack> contents = NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY);
		contents.set(4, new ItemStack(ModItems.LINK_SYNC_EMITTER));
		contents.set(9, new ItemStack(ModItems.HIDE_SYNC_TRIGGER_SOURCE));

		assertEquals(
			9,
			SmartNodeContainerData.resolvePreferredSlotForType(contents, SmartNodeContainerPlacementType.TRIGGER_SOURCE, 9)
		);
		assertEquals(
			4,
			SmartNodeContainerData.resolvePreferredSlotForType(contents, SmartNodeContainerPlacementType.TRIGGER_SOURCE, 18)
		);
	}

	@Test
	void resolvePreferredSlotForTypeShouldReturnNegativeOneForEmptyContents() {
		assertEquals(
			-1,
			SmartNodeContainerData.resolvePreferredSlotForType(
				NonNullList.withSize(SmartNodeContainerData.SLOT_COUNT, ItemStack.EMPTY),
				SmartNodeContainerPlacementType.TRIGGER_SOURCE,
				18
			)
		);
	}
}
