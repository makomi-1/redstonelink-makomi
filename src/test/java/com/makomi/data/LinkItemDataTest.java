package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
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
	 * getNodeType 对普通物品应返回 empty。
	 */
	@Test
	void getNodeTypeShouldReturnEmptyForNonPairableItem() {
		assertTrue(LinkItemData.getNodeType(new ItemStack(Items.STONE)).isEmpty());
	}
}
