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

	/**
	 * 同类型序号采集应执行增量追加，并保持去重。
	 */
	@Test
	void collectSerialShouldAppendAndDeduplicateWhenTypeMatches() {
		ItemStack stack = new ItemStack(Items.STONE);
		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.SERIAL,
				LinkNodeType.CORE,
				"1:3",
				"reserved-channel"
			)
		);

		QuickLinkToolData.SerialCollectOutcome appended = QuickLinkToolData.collectSerial(stack, LinkNodeType.CORE, 5L);
		assertEquals(QuickLinkToolData.SerialCollectAction.APPENDED, appended.action());
		assertEquals("1:3/5", appended.snapshot().serialCacheExpression());
		assertEquals("reserved-channel", appended.snapshot().channelCache());

		QuickLinkToolData.SerialCollectOutcome duplicate = QuickLinkToolData.collectSerial(stack, LinkNodeType.CORE, 3L);
		assertEquals(QuickLinkToolData.SerialCollectAction.DUPLICATE, duplicate.action());
		assertEquals("1:3/5", duplicate.snapshot().serialCacheExpression());
	}

	/**
	 * 不同类型序号采集应重建当前序号缓存。
	 */
	@Test
	void collectSerialShouldReplaceCacheWhenTypeChanges() {
		ItemStack stack = new ItemStack(Items.STONE);
		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.SERIAL,
				LinkNodeType.CORE,
				"1:3",
				"reserved-channel"
			)
		);

		QuickLinkToolData.SerialCollectOutcome replaced = QuickLinkToolData.collectSerial(stack, LinkNodeType.TRIGGER_SOURCE, 12L);
		assertEquals(QuickLinkToolData.SerialCollectAction.REPLACED, replaced.action());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, replaced.snapshot().serialCacheType());
		assertEquals("12", replaced.snapshot().serialCacheExpression());
		assertEquals("reserved-channel", replaced.snapshot().channelCache());
	}

	/**
	 * 清空缓存应保留当前模式与序号缓存类型。
	 */
	@Test
	void clearCachesShouldKeepModeAndSerialCacheType() {
		ItemStack stack = new ItemStack(Items.STONE);
		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.SERIAL,
				LinkNodeType.TRIGGER_SOURCE,
				"1:3/5",
				"channel-42"
			)
		);

		QuickLinkToolData.Snapshot cleared = QuickLinkToolData.clearCaches(stack);
		assertEquals(QuickLinkToolData.Mode.SERIAL, cleared.mode());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, cleared.serialCacheType());
		assertEquals("", cleared.serialCacheExpression());
		assertEquals("", cleared.channelCache());
	}
}
