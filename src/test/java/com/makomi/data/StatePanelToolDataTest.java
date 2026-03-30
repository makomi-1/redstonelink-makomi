package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * StatePanelToolData 读写与去重排序契约测试。
 */
@Tag("stable-core")
class StatePanelToolDataTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 空栈读取应返回空订阅列表。
	 */
	@Test
	void emptyStackShouldReturnEmptySubscriptions() {
		List<StatePanelToolData.SubscriptionEntry> entries = StatePanelToolData.readSubscriptions(new ItemStack(Items.STONE));
		assertTrue(entries.isEmpty());
	}

	/**
	 * 写入后应按 type + serial 去重并稳定排序。
	 */
	@Test
	void writeShouldNormalizeByTypeAndSerial() {
		ItemStack stack = new ItemStack(Items.STONE);
		StatePanelToolData.writeSubscriptions(
			stack,
			List.of(
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 3L),
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, 5L),
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 3L),
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, -1L)
			)
		);

		List<StatePanelToolData.SubscriptionEntry> entries = StatePanelToolData.readSubscriptions(stack);
		assertEquals(2, entries.size());
		assertEquals(LinkNodeType.CORE, entries.get(0).nodeType());
		assertEquals(3L, entries.get(0).serial());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, entries.get(1).nodeType());
		assertEquals(5L, entries.get(1).serial());
	}

	/**
	 * 合并订阅时应保留旧项并合并新项，最终结果仍去重排序。
	 */
	@Test
	void mergeShouldKeepExistingAndNormalize() {
		List<StatePanelToolData.SubscriptionEntry> merged = StatePanelToolData.mergeSubscriptions(
			List.of(
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 9L),
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, 4L)
			),
			LinkNodeType.CORE,
			List.of(9L, 2L, -3L)
		);

		assertEquals(3, merged.size());
		assertEquals(new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 2L), merged.get(0));
		assertEquals(new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 9L), merged.get(1));
		assertEquals(new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, 4L), merged.get(2));
	}

	/**
	 * 删除订阅应按 type + serial 精确匹配。
	 */
	@Test
	void removeShouldMatchTypeAndSerial() {
		ItemStack stack = new ItemStack(Items.STONE);
		StatePanelToolData.writeSubscriptions(
			stack,
			List.of(
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 7L),
				new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, 7L)
			)
		);

		assertFalse(StatePanelToolData.removeSubscription(stack, LinkNodeType.CORE, 0L));
		assertTrue(StatePanelToolData.removeSubscription(stack, LinkNodeType.CORE, 7L));
		assertEquals(1, StatePanelToolData.subscriptionCount(stack));
		assertEquals(LinkNodeType.TRIGGER_SOURCE, StatePanelToolData.readSubscriptions(stack).get(0).nodeType());
	}
}