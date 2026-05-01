package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.testsupport.TestMinecraftSupport;
import java.util.stream.LongStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkItemData 物品元数据读写契约测试。
 */
@Tag("stable-core")
class LinkItemDataTest {

	/**
	 * 初始化 Minecraft 基础注册表，确保 Item/ItemStack 在单测环境可用。
	 */
	@BeforeAll
	static void bootstrapRegistries() {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	/**
	 * serial/pair 字段应支持写入、读取与移除。
	 */
	@Test
	void serialAndPairFieldsShouldSupportWriteReadAndRemove() {
		ItemStack stack = new ItemStack(Items.STONE);

		assertEquals(0L, LinkItemData.getSerial(stack));
		assertEquals(0L, LinkItemData.getPairSerial(stack));

		LinkItemData.setSerial(stack, 42L);
		LinkItemData.setPairSerial(stack, 77L);
		assertEquals(42L, LinkItemData.getSerial(stack));
		assertEquals(77L, LinkItemData.getPairSerial(stack));

		LinkItemData.setSerial(stack, 0L);
		LinkItemData.setPairSerial(stack, -1L);
		assertEquals(0L, LinkItemData.getSerial(stack));
		assertEquals(0L, LinkItemData.getPairSerial(stack));
	}

	/**
	 * 频道快照字段应支持写入、读取与移除。
	 */
	@Test
	void channelFieldShouldSupportWriteReadAndRemove() {
		ItemStack stack = new ItemStack(Items.STONE);

		assertEquals(0L, LinkItemData.getChannel(stack));

		LinkItemData.setChannel(stack, 77L);
		assertEquals(77L, LinkItemData.getChannel(stack));

		LinkItemData.setChannel(stack, 0L);
		assertEquals(0L, LinkItemData.getChannel(stack));
	}

	/**
	 * linked serials 写入时应过滤非正数并升序存储。
	 */
	@Test
	void linkedSerialsShouldFilterAndSort() {
		ItemStack stack = new ItemStack(Items.STONE);
		Set<Long> input = new HashSet<>();
		input.add(9L);
		input.add(3L);
		input.add(5L);
		input.add(0L);
		input.add(-2L);

		LinkItemData.setLinkedSerials(stack, input);
		assertEquals(List.of(3L, 5L, 9L), LinkItemData.getLinkedSerials(stack));
		assertEquals(List.of("#3", "#5", "#9"), LinkItemData.getLinkedDisplayTexts(stack));
	}

	/**
	 * linked snapshot 应同时保存目标序号和展示文本。
	 */
	@Test
	void linkedSnapshotShouldStoreDisplayTexts() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkItemData.setLinkedSnapshot(stack, List.of(3L, 5L), List.of("门厅(#3)", "#5"));

		assertEquals(List.of(3L, 5L), LinkItemData.getLinkedSerials(stack));
		assertEquals(List.of("门厅(#3)", "#5"), LinkItemData.getLinkedDisplayTexts(stack));
	}

	/**
	 * linked serials 返回列表应为不可变快照。
	 */
	@Test
	void linkedSerialsResultShouldBeImmutable() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkItemData.setLinkedSerials(stack, Set.of(1L, 2L));

		List<Long> linked = LinkItemData.getLinkedSerials(stack);
		assertThrows(UnsupportedOperationException.class, () -> linked.add(3L));
	}

	/**
	 * 聚合序号组应支持去重、升序，并同步镜像顶部序号。
	 */
	@Test
	void serialGroupShouldNormalizeAndMirrorPrimarySerial() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkItemData.setSerialGroup(stack, List.of(9L, 3L, 5L, 3L, -1L, 0L));
		assertEquals(List.of(3L, 5L, 9L), LinkItemData.getSerialGroup(stack));
		assertEquals(3L, LinkItemData.getSerial(stack));
		assertTrue(LinkItemData.isAggregated(stack));
		assertEquals(3, LinkItemData.getSerialCount(stack));
	}

	/**
	 * 序号写回时应清理绑定在旧单件快照上的频道缓存，避免 Tooltip 残留旧值。
	 */
	@Test
	void setSerialShouldClearChannelSnapshot() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkItemData.setSerial(stack, 11L);
		LinkItemData.setChannel(stack, 29L);
		LinkItemData.setLinkedSnapshot(stack, List.of(31L), List.of("中控(#31)"));
		assertEquals(29L, LinkItemData.getChannel(stack));

		LinkItemData.setSerial(stack, 12L);
		assertEquals(0L, LinkItemData.getChannel(stack));
		assertEquals(List.of("#31"), LinkItemData.getLinkedDisplayTexts(stack));
	}

	/**
	 * 聚合序号组写入时应遵守 64 上限，并可计算剩余容量。
	 */
	@Test
	void serialGroupShouldRespectAggregateStackLimit() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkItemData.setSerialGroup(
			stack,
			LongStream.rangeClosed(1L, LinkItemData.AGGREGATE_STACK_LIMIT + 10L).boxed().toList()
		);

		assertEquals(LinkItemData.AGGREGATE_STACK_LIMIT, LinkItemData.getSerialCount(stack));
		assertEquals(
			LongStream.rangeClosed(1L, LinkItemData.AGGREGATE_STACK_LIMIT).boxed().toList(),
			LinkItemData.getSerialGroup(stack)
		);
		assertEquals(0, LinkItemData.getRemainingAggregateCapacity(stack));
	}

	/**
	 * 聚合序号组改写时应同时清理旧频道缓存，避免新顶部序号继承旧节点频道。
	 */
	@Test
	void serialGroupRewriteShouldClearChannelSnapshot() {
		ItemStack stack = new ItemStack(Items.STONE);

		LinkItemData.setSerial(stack, 31L);
		LinkItemData.setChannel(stack, 66L);
		LinkItemData.setLinkedSnapshot(stack, List.of(41L), List.of("目标(#41)"));
		assertEquals(66L, LinkItemData.getChannel(stack));

		LinkItemData.setSerialGroup(stack, List.of(41L, 42L));
		assertEquals(0L, LinkItemData.getChannel(stack));
		assertEquals(List.of(), LinkItemData.getLinkedDisplayTexts(stack));
	}

	/**
	 * 顶部序号移除后应自动回写新的顶部序号。
	 */
	@Test
	void removeTopSerialShouldPromoteNextSerial() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkItemData.setSerialGroup(stack, List.of(3L, 5L, 9L));

		assertEquals(3L, LinkItemData.removeTopSerial(stack));
		assertEquals(List.of(5L, 9L), LinkItemData.getSerialGroup(stack));
		assertEquals(5L, LinkItemData.getSerial(stack));
	}

	/**
	 * 右键二分应保留较小序号一侧，并返回较大序号一侧。
	 */
	@Test
	void splitUpperHalfShouldKeepLowerHalfOnStack() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkItemData.setSerialGroup(stack, List.of(1L, 2L, 3L, 4L, 5L));

		assertEquals(List.of(3L, 4L, 5L), LinkItemData.splitUpperHalf(stack));
		assertEquals(List.of(1L, 2L), LinkItemData.getSerialGroup(stack));
		assertEquals(1L, LinkItemData.getSerial(stack));
	}

	/**
	 * containsSerial 应同时兼容单序号与聚合序号组。
	 */
	@Test
	void containsSerialShouldSupportAggregateAndSingleStack() {
		ItemStack single = new ItemStack(Items.STONE);
		LinkItemData.setSerial(single, 7L);
		assertTrue(LinkItemData.containsSerial(single, 7L));
		assertFalse(LinkItemData.containsSerial(single, 8L));

		ItemStack aggregate = new ItemStack(Items.STONE);
		LinkItemData.setSerialGroup(aggregate, List.of(11L, 13L, 17L));
		assertTrue(LinkItemData.containsSerial(aggregate, 13L));
		assertFalse(LinkItemData.containsSerial(aggregate, 19L));
	}

	/**
	 * 销毁即退役标记应支持双向切换。
	 */
	@Test
	void destroyRetireCandidateShouldToggle() {
		ItemStack stack = new ItemStack(Items.STONE);

		assertFalse(LinkItemData.isDestroyRetireCandidate(stack));
		LinkItemData.setDestroyRetireCandidate(stack, true);
		assertTrue(LinkItemData.isDestroyRetireCandidate(stack));
		LinkItemData.setDestroyRetireCandidate(stack, false);
		assertFalse(LinkItemData.isDestroyRetireCandidate(stack));
	}

	/**
	 * 同步遥控器强度应支持 `0~15`，并继续镜像到二态贴图状态。
	 */
	@Test
	void syncLinkerSignalStrengthShouldMirrorCustomModelData() {
		ItemStack stack = new ItemStack(Items.STONE);
		LinkItemData.setSerial(stack, 7L);

		assertEquals(0, LinkItemData.getSyncLinkerSignalStrength(stack));
		assertNull(stack.get(DataComponents.CUSTOM_MODEL_DATA));

		LinkItemData.setSyncLinkerSignalStrength(stack, 7);
		assertEquals(7, LinkItemData.getSyncLinkerSignalStrength(stack));
		CustomModelData mediumModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		assertNotNull(mediumModelData);
		assertEquals(TestMinecraftSupport.singleFloatCustomModelData(1), mediumModelData);

		LinkItemData.setSyncLinkerSignalStrength(stack, 15);
		assertEquals(15, LinkItemData.getSyncLinkerSignalStrength(stack));
		CustomModelData activeModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		assertNotNull(activeModelData);
		assertEquals(TestMinecraftSupport.singleFloatCustomModelData(1), activeModelData);

		LinkItemData.setSyncLinkerSignalStrength(stack, 999);
		assertEquals(15, LinkItemData.getSyncLinkerSignalStrength(stack));

		LinkItemData.setSyncLinkerSignalStrength(stack, -3);
		assertEquals(0, LinkItemData.getSyncLinkerSignalStrength(stack));
		assertNull(stack.get(DataComponents.CUSTOM_MODEL_DATA));

		LinkItemData.setSyncLinkerSignalStrength(stack, 0);
		assertEquals(0, LinkItemData.getSyncLinkerSignalStrength(stack));
		assertNull(stack.get(DataComponents.CUSTOM_MODEL_DATA));
	}

	/**
	 * getNodeType 对普通物品应返回 empty。
	 */
	@Test
	void getNodeTypeShouldReturnEmptyForNonPairableItem() {
		assertTrue(LinkItemData.getNodeType(new ItemStack(Items.STONE)).isEmpty());
	}
}
