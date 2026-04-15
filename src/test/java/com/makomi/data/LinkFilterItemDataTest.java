package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkFilterItemData 物品配置读写契约测试。
 */
@Tag("stable-core")
class LinkFilterItemDataTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 空物品应回退到默认过滤器配置。
	 */
	@Test
	void emptyStackShouldReturnDefaultSnapshot() {
		LinkFilterConfigSnapshot snapshot = LinkFilterItemData.read(new ItemStack(Items.STONE));

		assertEquals(new LinkFilterConfigSnapshot("", null, null, 15, null), snapshot);
	}

	/**
	 * 写入后应完整保留过滤器配置字段。
	 */
	@Test
	void writeShouldPreserveSnapshotFields() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkFilterConfigSnapshot original = new LinkFilterConfigSnapshot(
			"1:3/7/9:12",
			LinkFilterNodeSetMode.BLOCKLIST,
			LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
			6,
			LinkFilterSignalMode.UPPER_BOUND
		);

		LinkFilterItemData.write(stack, original);

		assertEquals(original, LinkFilterItemData.read(stack));
	}

	/**
	 * 过滤器物品展示别名应支持独立读写，并对空白输入归一化清空。
	 */
	@Test
	void displayAliasShouldRoundTripIndependently() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkFilterItemData.setDisplayAlias(stack, " 门厅A ");
		assertEquals("门厅A", LinkFilterItemData.getDisplayAlias(stack));

		LinkFilterItemData.setDisplayAlias(stack, "   ");
		assertEquals("", LinkFilterItemData.getDisplayAlias(stack));
	}

	/**
	 * tooltip 节点集文本应转为结构化表达式并按上限截断。
	 */
	@Test
	void tooltipSerialExpressionShouldBeStructuredAndTruncated() {
		LinkFilterConfigSnapshot snapshot = new LinkFilterConfigSnapshot(
			"1/2/3/4/5/6/7/8/9/10",
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		assertEquals("1:10", LinkFilterItemData.buildTooltipSerialExpressionText(snapshot, 48));
		assertEquals("1:10", LinkFilterItemData.buildTooltipSerialExpressionText(snapshot, 6));
	}
}
