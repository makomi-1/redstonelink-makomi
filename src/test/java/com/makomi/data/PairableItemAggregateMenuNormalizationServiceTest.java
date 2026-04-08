package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 第二层聚合栈菜单即时处理服务测试。
 */
@Tag("stable-core")
class PairableItemAggregateMenuNormalizationServiceTest {
	/**
	 * 初始化 Minecraft 基础注册表，确保 Item/ItemStack 在单测环境可用。
	 */
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void clearClickContexts() {
		PairableItemAggregateMenuNormalizationService.clearClickContextsForTest();
	}

	/**
	 * `InventoryMenu` 路由应与原版关键槽段一致。
	 */
	@Test
	void resolveInventoryMenuRoutePlanShouldMatchVanillaRanges() {
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveInventoryMenuRoutePlan(0),
			new RouteExpectation(9, 45, true)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveInventoryMenuRoutePlan(9),
			new RouteExpectation(36, 45, false)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveInventoryMenuRoutePlan(40),
			new RouteExpectation(9, 36, false)
		);
	}

	/**
	 * `CraftingMenu` 路由应与原版关键槽段一致。
	 */
	@Test
	void resolveCraftingMenuRoutePlanShouldMatchVanillaRanges() {
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveCraftingMenuRoutePlan(0),
			new RouteExpectation(10, 46, true)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveCraftingMenuRoutePlan(10),
			new RouteExpectation(1, 10, false),
			new RouteExpectation(37, 46, false)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveCraftingMenuRoutePlan(40),
			new RouteExpectation(10, 37, false)
		);
	}

	/**
	 * 炉子类菜单路由应与原版关键槽段一致。
	 */
	@Test
	void resolveAbstractFurnaceRoutePlanShouldMatchVanillaRanges() {
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveAbstractFurnaceRoutePlan(2, false, false),
			new RouteExpectation(3, 39, true)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveAbstractFurnaceRoutePlan(5, true, false),
			new RouteExpectation(0, 1, false)
		);
		assertRoutePlan(
			PairableItemAggregateMenuNormalizationService.resolveAbstractFurnaceRoutePlan(33, false, false),
			new RouteExpectation(3, 30, false)
		);
	}

	/**
	 * `InventoryMenu` 从主背包 `QUICK_MOVE` 时，只应归并快捷栏目标槽段，不应动主背包其他槽位。
	 */
	@Test
	void handleQuickMoveShouldOnlyNormalizeHotbarSegmentInInventoryMenu() {
		InventoryMenu menu = new InventoryMenu(new Inventory(null), false, null);
		menu.slots.get(36).set(stackWithSerials(Items.STONE, List.of(1L)));
		menu.slots.get(37).set(stackWithSerials(Items.STONE, List.of(2L)));
		menu.slots.get(38).set(stackWithSerials(Items.STONE, List.of(3L)));
		menu.slots.get(10).set(stackWithSerials(Items.STONE, List.of(40L)));
		menu.slots.get(11).set(stackWithSerials(Items.STONE, List.of(41L)));

		assertTrue(
			PairableItemAggregateMenuNormalizationService.handleQuickMove(
				menu,
				9,
				null,
				stackWithSerials(Items.STONE, List.of(99L))
			)
		);
		assertEquals(List.of(1L, 2L, 3L), LinkItemData.getSerialGroup(menu.slots.get(36).getItem()));
		assertTrue(menu.slots.get(37).getItem().isEmpty());
		assertTrue(menu.slots.get(38).getItem().isEmpty());
		assertEquals(List.of(40L), LinkItemData.getSerialGroup(menu.slots.get(10).getItem()));
		assertEquals(List.of(41L), LinkItemData.getSerialGroup(menu.slots.get(11).getItem()));
	}

	/**
	 * `CraftingMenu` 结果槽 `QUICK_MOVE` 应按原版反向扫描优先保留高索引落点。
	 */
	@Test
	void handleQuickMoveShouldRespectReverseScanForCraftingResult() {
		CraftingMenu menu = new CraftingMenu(0, new Inventory(null));
		menu.slots.get(45).set(stackWithSerials(Items.STONE, List.of(1L)));
		menu.slots.get(44).set(stackWithSerials(Items.STONE, List.of(2L)));
		menu.slots.get(10).set(stackWithSerials(Items.STONE, List.of(3L)));

		assertTrue(
			PairableItemAggregateMenuNormalizationService.handleQuickMove(
				menu,
				0,
				null,
				new ItemStack(Items.STONE)
			)
		);
		assertEquals(List.of(1L, 2L, 3L), LinkItemData.getSerialGroup(menu.slots.get(45).getItem()));
		assertTrue(menu.slots.get(44).getItem().isEmpty());
		assertTrue(menu.slots.get(10).getItem().isEmpty());
	}

	/**
	 * 双区通用容器兜底路由下，只应归并玩家背包后缀，不应误动容器区。
	 */
	@Test
	void handleQuickMoveShouldKeepGenericContainerAreaSeparated() {
		SimpleContainer container = new SimpleContainer(2);
		Inventory inventory = new Inventory(null);
		TestMenu menu = new TestMenu();
		menu.addTestSlot(new Slot(container, 0, 0, 0));
		menu.addTestSlot(new Slot(container, 1, 0, 0));
		addPlayerSuffixSlots(menu, inventory);

		menu.slots.get(2).set(stackWithSerials(Items.STONE, List.of(10L)));
		menu.slots.get(3).set(stackWithSerials(Items.STONE, List.of(11L)));
		menu.slots.get(4).set(stackWithSerials(Items.STONE, List.of(12L)));
		menu.slots.get(1).set(stackWithSerials(Items.STONE, List.of(80L)));

		assertTrue(
			PairableItemAggregateMenuNormalizationService.handleQuickMove(
				menu,
				0,
				null,
				stackWithSerials(Items.STONE, List.of(9L))
			)
		);
		assertTrue(menu.slots.get(2).getItem().isEmpty());
		assertTrue(menu.slots.get(3).getItem().isEmpty());
		assertEquals(List.of(10L, 11L, 12L), LinkItemData.getSerialGroup(menu.slots.get(4).getItem()));
		assertEquals(List.of(80L), LinkItemData.getSerialGroup(menu.slots.get(1).getItem()));
	}

	private static void assertRoutePlan(
		PairableItemAggregateMenuNormalizationService.QuickMoveRoutePlan plan,
		RouteExpectation... expectations
	) {
		assertNotNull(plan);
		assertEquals(expectations.length, plan.segments().size());
		for (int index = 0; index < expectations.length; index++) {
			PairableItemAggregateMenuNormalizationService.QuickMoveRouteSegment segment = plan.segments().get(index);
			RouteExpectation expectation = expectations[index];
			assertEquals(expectation.startInclusive(), segment.startInclusive());
			assertEquals(expectation.endExclusive(), segment.endExclusive());
			assertEquals(expectation.reverse(), segment.reverse());
		}
	}

	private static void addPlayerSuffixSlots(TestMenu menu, Inventory inventory) {
		for (int index = 0; index < 36; index++) {
			menu.addTestSlot(new Slot(inventory, index, 0, 0));
		}
	}

	private static ItemStack stackWithSerials(Item item, List<Long> serials) {
		ItemStack stack = new ItemStack(item);
		LinkItemData.setSerialGroup(stack, serials);
		return stack;
	}

	/**
	 * 测试用双区菜单。
	 */
	private static final class TestMenu extends AbstractContainerMenu {
		private TestMenu() {
			super(null, 0);
		}

		private void addTestSlot(Slot slot) {
			addSlot(slot);
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	/**
	 * 槽段期望。
	 */
	private record RouteExpectation(int startInclusive, int endExclusive, boolean reverse) {
	}
}
