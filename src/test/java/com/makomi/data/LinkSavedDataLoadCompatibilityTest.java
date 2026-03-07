package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 反序列化兼容性与健壮性测试。
 */
@Tag("stable-core")
class LinkSavedDataLoadCompatibilityTest {

	/**
	 * save/load 往返后应保持关键状态一致。
	 */
	@Test
	void saveAndLoadRoundTripShouldPreserveTopology() {
		LinkSavedData source = new LinkSavedData();
		long coreSerial = source.allocateSerial(LinkNodeType.CORE);
		long buttonSerial = source.allocateSerial(LinkNodeType.BUTTON);
		source.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(20, 64, 20), LinkNodeType.CORE);
		source.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(21, 64, 20), LinkNodeType.BUTTON);
		source.toggleLink(buttonSerial, coreSerial);

		CompoundTag saved = source.save(new CompoundTag(), null);
		LinkSavedData restored = invokeLoad(saved);

		assertTrue(restored.findNode(LinkNodeType.CORE, coreSerial).isPresent());
		assertTrue(restored.findNode(LinkNodeType.BUTTON, buttonSerial).isPresent());
		assertTrue(restored.getLinkedCores(buttonSerial).contains(coreSerial));
		assertTrue(restored.getLinkedButtons(coreSerial).contains(buttonSerial));
	}

	/**
	 * 兼容旧版本 nextSerial 字段：CORE/BUTTON 都应从旧计数器继续分配。
	 */
	@Test
	void loadShouldSupportLegacyNextSerialKey() {
		CompoundTag legacy = new CompoundTag();
		legacy.putLong("nextSerial", 5L);

		LinkSavedData restored = invokeLoad(legacy);
		long coreSerial = restored.allocateSerial(LinkNodeType.CORE);
		long buttonSerial = restored.allocateSerial(LinkNodeType.BUTTON);

		assertEquals(5L, coreSerial);
		assertEquals(5L, buttonSerial);
	}

	/**
	 * 非法节点条目（无效序列号/维度）应在加载时被过滤。
	 */
	@Test
	void loadShouldFilterInvalidNodeEntries() {
		CompoundTag dataTag = new CompoundTag();
		ListTag nodes = new ListTag();

		CompoundTag validNode = new CompoundTag();
		validNode.putLong("serial", 9L);
		validNode.putString("dimension", Level.OVERWORLD.location().toString());
		validNode.putLong("pos", new BlockPos(1, 2, 3).asLong());
		validNode.putString("type", "CORE");
		nodes.add(validNode);

		CompoundTag invalidSerialNode = new CompoundTag();
		invalidSerialNode.putLong("serial", 0L);
		invalidSerialNode.putString("dimension", Level.OVERWORLD.location().toString());
		invalidSerialNode.putLong("pos", new BlockPos(4, 5, 6).asLong());
		invalidSerialNode.putString("type", "CORE");
		nodes.add(invalidSerialNode);

		CompoundTag invalidDimensionNode = new CompoundTag();
		invalidDimensionNode.putLong("serial", 10L);
		invalidDimensionNode.putString("dimension", "bad::dimension");
		invalidDimensionNode.putLong("pos", new BlockPos(7, 8, 9).asLong());
		invalidDimensionNode.putString("type", "BUTTON");
		nodes.add(invalidDimensionNode);

		dataTag.put("nodes", nodes);

		LinkSavedData restored = invokeLoad(dataTag);
		assertTrue(restored.findNode(LinkNodeType.CORE, 9L).isPresent());
		assertFalse(restored.findNode(LinkNodeType.CORE, 0L).isPresent());
		assertFalse(restored.findNode(LinkNodeType.BUTTON, 10L).isPresent());
	}

	/**
	 * 链接数据中的非法序列号应在加载时被过滤，仅保留正数。
	 */
	@Test
	void loadShouldFilterInvalidLinkSerials() {
		CompoundTag dataTag = new CompoundTag();

		ListTag links = new ListTag();
		CompoundTag linkEntry = new CompoundTag();
		linkEntry.putLong("buttonSerial", 77L);
		linkEntry.putLongArray("coreSerials", new long[] { 0L, 11L, -3L, 12L });
		links.add(linkEntry);
		dataTag.put("links", links);

		LinkSavedData restored = invokeLoad(dataTag);
		assertEquals(Set.of(11L, 12L), restored.getLinkedCores(77L));
	}

	/**
	 * 反序列化调用失败时应抛出明确异常，便于测试定位。
	 */
	@Test
	void invokeLoadShouldThrowOnReflectionFailure() {
		assertThrows(IllegalStateException.class, () -> invokeLoadViaMethodName("notExistingLoadMethod", new CompoundTag()));
	}

	private static LinkSavedData invokeLoad(CompoundTag tag) {
		return invokeLoadViaMethodName("load", tag);
	}

	/**
	 * 通过反射调用私有静态 load 方法，测试仅用于兼容行为验证。
	 */
	private static LinkSavedData invokeLoadViaMethodName(String methodName, CompoundTag tag) {
		try {
			Method loadMethod = LinkSavedData.class.getDeclaredMethod(methodName, CompoundTag.class, HolderLookup.Provider.class);
			loadMethod.setAccessible(true);
			return (LinkSavedData) loadMethod.invoke(null, tag, null);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
			throw new IllegalStateException("failed to invoke LinkSavedData.load by reflection", ex);
		}
	}
}
