package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
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
		assertEquals(QuickLinkToolData.ApplyEditMode.REPLACE, snapshot.applyEditMode());
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
				" demo-channel ",
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		assertEquals(QuickLinkToolData.Mode.CHANNEL, snapshot.mode());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, snapshot.serialCacheType());
		assertEquals("1:3/5", snapshot.serialCacheExpression());
		assertEquals("demo-channel", snapshot.channelCache());
		assertEquals(QuickLinkToolData.ApplyEditMode.REMOVE, snapshot.applyEditMode());
		assertEquals(new CustomModelData(1), stack.get(DataComponents.CUSTOM_MODEL_DATA));
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
	 * 应用编辑模式循环切换应在 `replace/append/remove` 间往返。
	 */
	@Test
	void cycleApplyEditModeShouldToggleAcrossAllModes() {
		ItemStack stack = new ItemStack(Items.STONE);
		assertEquals(QuickLinkToolData.ApplyEditMode.APPEND, QuickLinkToolData.cycleApplyEditMode(stack).applyEditMode());
		assertEquals(QuickLinkToolData.ApplyEditMode.REMOVE, QuickLinkToolData.cycleApplyEditMode(stack).applyEditMode());
		assertEquals(QuickLinkToolData.ApplyEditMode.REPLACE, QuickLinkToolData.cycleApplyEditMode(stack).applyEditMode());
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
				"reserved-channel",
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);

		QuickLinkToolData.SerialCollectOutcome appended = QuickLinkToolData.collectSerial(stack, LinkNodeType.CORE, 5L);
		assertEquals(QuickLinkToolData.SerialCollectAction.APPENDED, appended.action());
		assertEquals("1:3/5", appended.snapshot().serialCacheExpression());
		assertEquals("reserved-channel", appended.snapshot().channelCache());
		assertEquals(QuickLinkToolData.ApplyEditMode.APPEND, appended.snapshot().applyEditMode());

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
				"reserved-channel",
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);

		QuickLinkToolData.SerialCollectOutcome replaced = QuickLinkToolData.collectSerial(stack, LinkNodeType.TRIGGER_SOURCE, 12L);
		assertEquals(QuickLinkToolData.SerialCollectAction.REPLACED, replaced.action());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, replaced.snapshot().serialCacheType());
		assertEquals("12", replaced.snapshot().serialCacheExpression());
		assertEquals("reserved-channel", replaced.snapshot().channelCache());
		assertEquals(QuickLinkToolData.ApplyEditMode.REMOVE, replaced.snapshot().applyEditMode());
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
				"channel-42",
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);

		QuickLinkToolData.Snapshot cleared = QuickLinkToolData.clearCaches(stack);
		assertEquals(QuickLinkToolData.Mode.SERIAL, cleared.mode());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, cleared.serialCacheType());
		assertEquals("", cleared.serialCacheExpression());
		assertEquals("", cleared.channelCache());
		assertEquals(QuickLinkToolData.ApplyEditMode.APPEND, cleared.applyEditMode());
	}

	/**
	 * 模式镜像应驱动物品贴图切换。
	 */
	@Test
	void quickLinkModelStateShouldFollowMode() {
		ItemStack stack = new ItemStack(Items.STONE);
		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.CHANNEL,
				LinkNodeType.CORE,
				"",
				"channel-42",
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals(new CustomModelData(1), stack.get(DataComponents.CUSTOM_MODEL_DATA));

		QuickLinkToolData.write(
			stack,
			new QuickLinkToolData.Snapshot(
				QuickLinkToolData.Mode.SERIAL,
				LinkNodeType.CORE,
				"",
				"",
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertNull(stack.get(DataComponents.CUSTOM_MODEL_DATA));
	}
}
