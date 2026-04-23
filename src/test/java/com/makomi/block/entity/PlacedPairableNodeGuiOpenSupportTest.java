package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 已放置节点 reopen GUI 前 serial 自愈逻辑回归测试。
 */
@Tag("stable-core")
class PlacedPairableNodeGuiOpenSupportTest {
	/**
	 * 已退役 core serial 在 reopen GUI 时应重分配。
	 */
	@Test
	void resolveSerialForPairingOpenShouldReallocateRetiredCoreSerial() {
		LinkSavedData savedData = new LinkSavedData();
		BlockPos pos = new BlockPos(20, 64, 20);
		long retiredSerial = savedData.allocateSerial(LinkNodeType.CORE);
		savedData.registerNode(retiredSerial, Level.OVERWORLD, pos, LinkNodeType.CORE);
		savedData.retireNode(LinkNodeType.CORE, retiredSerial);

		long resolvedSerial = PlacedPairableNodeGuiOpenSupport.resolveSerialForPairingOpen(
			savedData,
			LinkNodeType.CORE,
			retiredSerial,
			Level.OVERWORLD,
			pos
		);

		assertNotEquals(retiredSerial, resolvedSerial);
		assertTrue(savedData.isSerialAllocated(LinkNodeType.CORE, resolvedSerial));
		assertFalse(savedData.isSerialRetired(LinkNodeType.CORE, resolvedSerial));
		assertTrue(savedData.isSerialRetired(LinkNodeType.CORE, retiredSerial));
	}

	/**
	 * 仍然有效且位置一致的 triggerSource serial 在 reopen GUI 时应保持不变。
	 */
	@Test
	void resolveSerialForPairingOpenShouldReuseValidTriggerSourceSerial() {
		LinkSavedData savedData = new LinkSavedData();
		BlockPos pos = new BlockPos(28, 64, 28);
		long serial = savedData.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		savedData.registerNode(serial, Level.OVERWORLD, pos, LinkNodeType.TRIGGER_SOURCE);

		long resolvedSerial = PlacedPairableNodeGuiOpenSupport.resolveSerialForPairingOpen(
			savedData,
			LinkNodeType.TRIGGER_SOURCE,
			serial,
			Level.OVERWORLD,
			pos
		);

		assertEquals(serial, resolvedSerial);
		assertTrue(savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, resolvedSerial));
		assertFalse(savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, resolvedSerial));
	}
}
