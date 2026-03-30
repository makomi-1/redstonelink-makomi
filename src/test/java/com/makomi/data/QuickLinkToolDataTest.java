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
 * QuickLinkToolData 读写契约测试。
 */
@Tag("stable-core")
class QuickLinkToolDataTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 默认空栈应回退到 `serial + core` 初始快照。
	 */
	@Test
	void emptyStackShouldUseDefaultSnapshot() {
		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(new ItemStack(Items.STONE));
		assertEquals(QuickLinkToolData.Mode.SERIAL, snapshot.mode());
		assertEquals(LinkNodeType.CORE, snapshot.serialCacheType());
		assertEquals("", snapshot.serialCacheExpression());
		assertEquals("", snapshot.channelCache());
	}

	/**
	 * 写入后应能按规范回读并自动裁剪空白。
	 */
	@Test
	void snapshotShouldRoundTripWithNormalization() {
		ItemStack stack = new ItemStack(Items.STONE);
		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.CHANNEL,
				LinkNodeType.TRIGGER_SOURCE,
				" 1:3/5 ",
				" demo-channel "
			)
		);

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		assertEquals(QuickLinkToolData.Mode.CHANNEL, snapshot.mode());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, snapshot.serialCacheType());
		assertEquals("1:3/5", snapshot.serialCacheExpression());
		assertEquals("demo-channel", snapshot.channelCache());
	}

	/**
	 * 模式循环切换应在 `serial/channel` 间往返。
	 */
	@Test
	void cycleModeShouldToggleBetweenSerialAndChannel() {
		ItemStack stack = new ItemStack(Items.STONE);
		assertEquals(QuickLinkToolData.Mode.CHANNEL, QuickLinkToolData.cycleMode(stack).mode());
		assertEquals(QuickLinkToolData.Mode.SERIAL, QuickLinkToolData.cycleMode(stack).mode());
	}
}
