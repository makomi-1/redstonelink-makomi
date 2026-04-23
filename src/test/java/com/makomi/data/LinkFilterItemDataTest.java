package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
	 * 频道模式写入后应保留目标模式与频道值，并清空序号表达式。
	 */
	@Test
	void writeShouldPreserveChannelTargetFields() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkFilterConfigSnapshot original = new LinkFilterConfigSnapshot(
			"1/3/5",
			LinkFilterTargetMode.CHANNEL,
			88L,
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		LinkFilterItemData.write(stack, original);

		assertEquals(
			new LinkFilterConfigSnapshot(
				"",
				LinkFilterTargetMode.CHANNEL,
				88L,
				LinkFilterNodeSetMode.WHITELIST,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED
			),
			LinkFilterItemData.read(stack)
		);
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
	 * tooltip 节点集文本在无别名缓存时应回退为 `#序号` 列表，并按上限截断。
	 */
	@Test
	void tooltipSerialExpressionShouldFallbackToSerialTokensAndTruncate() {
		LinkFilterConfigSnapshot snapshot = new LinkFilterConfigSnapshot(
			"1/2/3/4/5/6/7/8/9/10",
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		assertEquals("#1/#2/#3/#4/#5/#6/#7/#8/#9/#10", LinkFilterItemData.buildTooltipSerialExpressionText(snapshot, 48));
		assertEquals("#1(+9)", LinkFilterItemData.buildTooltipSerialExpressionText(snapshot, 6));
	}

	/**
	 * tooltip 目标文本在频道模式下应显示频道值。
	 */
	@Test
	void tooltipTargetTextShouldDisplayChannelValueInChannelMode() {
		LinkFilterConfigSnapshot snapshot = new LinkFilterConfigSnapshot(
			"",
			LinkFilterTargetMode.CHANNEL,
			77L,
			LinkFilterNodeSetMode.BLOCKLIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		assertEquals("77", LinkFilterItemData.buildTooltipTargetText(snapshot, 48));
	}

	/**
	 * 节点集展示文本缓存应支持独立读写，并供 tooltip 优先使用别名文本。
	 */
	@Test
	void nodeSetDisplayTextsShouldRoundTripAndFeedTooltip() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkFilterConfigSnapshot snapshot = new LinkFilterConfigSnapshot(
			"3/7",
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		LinkFilterItemData.write(stack, snapshot);
		LinkFilterItemData.setNodeSetDisplayTexts(stack, snapshot, List.of("中控A(#3)", "#7"));

		assertEquals(List.of("中控A(#3)", "#7"), LinkFilterItemData.getNodeSetDisplayTexts(stack));
		assertEquals(
			"中控A(#3)/#7",
			LinkFilterItemData.buildTooltipTargetText(snapshot, LinkFilterItemData.getNodeSetDisplayTexts(stack), 64)
		);
	}

	/**
	 * 节点集展示文本缓存数量错位时，应回退到 `#序号`，避免 tooltip 错位显示。
	 */
	@Test
	void tooltipTargetTextShouldFallbackToSerialTokensWhenDisplayTextsMismatch() {
		LinkFilterConfigSnapshot snapshot = new LinkFilterConfigSnapshot(
			"3/7",
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);

		assertEquals("#3/#7", LinkFilterItemData.buildTooltipTargetText(snapshot, List.of("中控A(#3)"), 64));
	}
}
