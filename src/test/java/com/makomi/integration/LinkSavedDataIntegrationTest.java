package com.makomi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 链接数据的集成级回归测试。
 */
@Tag("integration")
class LinkSavedDataIntegrationTest {
	/**
	 * 注册节点、建立链接并持久化后，关键关系应保持一致。
	 */
	@Test
	void saveAndLoadShouldPreserveLinkTopology() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long buttonSerial = data.allocateSerial(LinkNodeType.BUTTON);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(10, 64, 10), LinkNodeType.CORE);
		data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(11, 64, 10), LinkNodeType.BUTTON);
		data.toggleLink(buttonSerial, coreSerial);

		CompoundTag saved = data.save(new CompoundTag(), null);
		LinkSavedData restored = invokeLoad(saved);

		assertTrue(restored.findNode(LinkNodeType.CORE, coreSerial).isPresent());
		assertTrue(restored.findNode(LinkNodeType.BUTTON, buttonSerial).isPresent());
		assertTrue(restored.getLinkedCores(buttonSerial).contains(coreSerial));
		assertTrue(restored.getLinkedButtons(coreSerial).contains(buttonSerial));
	}

	/**
	 * 通过反射调用私有 load 方法，避免直接暴露内部接口。
	 */
	private static LinkSavedData invokeLoad(CompoundTag tag) {
		try {
			var loadMethod = LinkSavedData.class.getDeclaredMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
			loadMethod.setAccessible(true);
			return (LinkSavedData) loadMethod.invoke(null, tag, null);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to invoke LinkSavedData.load by reflection", ex);
		}
	}
}
