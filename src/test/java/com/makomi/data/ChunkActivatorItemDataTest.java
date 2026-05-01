package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.testsupport.TestMinecraftSupport;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ChunkActivatorItemData 稳定契约测试。
 */
@Tag("stable-core")
class ChunkActivatorItemDataTest {
	@BeforeAll
	static void bootstrapRegistries() {
		TestMinecraftSupport.bootstrapMinecraft();
	}

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

	/**
	 * 两套节点集展示文本缓存应分别读写，并为当前 activeType 的 tooltip 提供别名文本。
	 */
	@Test
	void nodeSetDisplayTextsShouldRoundTripPerTypeAndFeedActiveTooltip() {
		ItemStack stack = new ItemStack(Items.STONE);
		ChunkActivatorConfigStateSnapshot snapshot = new ChunkActivatorConfigStateSnapshot(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("1/2", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("7/8", ChunkActivatorMode.RESIDENT)
		);

		ChunkActivatorItemData.write(stack, snapshot);
		ChunkActivatorItemData.setNodeSetDisplayTexts(stack, snapshot, LinkNodeType.TRIGGER_SOURCE, List.of("#1", "前置B(#2)"));
		ChunkActivatorItemData.setNodeSetDisplayTexts(stack, snapshot, LinkNodeType.CORE, List.of("中控A(#7)", "#8"));

		assertEquals(List.of("#1", "前置B(#2)"), ChunkActivatorItemData.getNodeSetDisplayTexts(stack, LinkNodeType.TRIGGER_SOURCE));
		assertEquals(List.of("中控A(#7)", "#8"), ChunkActivatorItemData.getNodeSetDisplayTexts(stack, LinkNodeType.CORE));
		assertEquals(List.of("中控A(#7)", "#8"), ChunkActivatorItemData.getActiveNodeSetDisplayTexts(stack));
		assertEquals(
			"中控A(#7)/#8",
			ChunkActivatorItemData.buildTooltipSerialExpressionText(
				snapshot.activeConfig(),
				ChunkActivatorItemData.getActiveNodeSetDisplayTexts(stack),
				64
			)
		);
	}
}
