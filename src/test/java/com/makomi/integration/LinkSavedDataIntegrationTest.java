package com.makomi.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.testsupport.TestMinecraftSupport;
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
		long triggerSourceSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(10, 64, 10), LinkNodeType.CORE);
		data.registerNode(triggerSourceSerial, Level.OVERWORLD, new BlockPos(11, 64, 10), LinkNodeType.TRIGGER_SOURCE);
		data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		CompoundTag saved = TestMinecraftSupport.saveSavedData(data);
		LinkSavedData restored = invokeLoad(saved);

		assertTrue(restored.findNode(LinkNodeType.CORE, coreSerial).isPresent());
		assertTrue(restored.findNode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial).isPresent());
		assertTrue(restored.getLinkedCoresByTriggerSource(triggerSourceSerial).contains(coreSerial));
		assertTrue(restored.getLinkedTriggerSourcesByCore(coreSerial).contains(triggerSourceSerial));
	}

	/**
	 * 建立→解除→再绑定流程应保持一致性。
	 */
	@Test
	void toggleLinkFlowShouldRemainConsistent() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long triggerSourceSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(30, 64, 30), LinkNodeType.CORE);
		data.registerNode(triggerSourceSerial, Level.OVERWORLD, new BlockPos(31, 64, 30), LinkNodeType.TRIGGER_SOURCE);

		boolean linked = data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
		assertTrue(linked);
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceSerial).contains(coreSerial));

		boolean unlinked = data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
		assertTrue(!unlinked);
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceSerial).isEmpty());
		assertTrue(data.getLinkedTriggerSourcesByCore(coreSerial).isEmpty());

		boolean linkedAgain = data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
		assertTrue(linkedAgain);
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceSerial).contains(coreSerial));
	}

	/**
	 * 通过反射调用私有 load 方法，避免直接暴露内部接口。
	 */
	private static LinkSavedData invokeLoad(CompoundTag tag) {
		return TestMinecraftSupport.invokePrivateStaticLoad(LinkSavedData.class, LinkSavedData.class, tag);
	}
}
