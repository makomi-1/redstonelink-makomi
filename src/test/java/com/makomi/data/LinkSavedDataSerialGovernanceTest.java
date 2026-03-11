package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 序列号治理与审计行为回归测试。
 */
@Tag("stable-core")
class LinkSavedDataSerialGovernanceTest {

	/**
	 * 退役节点应清理链接并记录退役状态。
	 */
	@Test
	void retireNodeShouldClearLinksAndMarkRetired() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long buttonSerial = data.allocateSerial(LinkNodeType.BUTTON);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(5, 64, 5), LinkNodeType.CORE);
		data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(6, 64, 5), LinkNodeType.BUTTON);
		data.toggleLink(buttonSerial, coreSerial);

		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.BUTTON, buttonSerial);

		assertTrue(result.nodeRemoved());
		assertEquals(1, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialAllocated(LinkNodeType.BUTTON, buttonSerial));
		assertTrue(data.isSerialRetired(LinkNodeType.BUTTON, buttonSerial));
		assertTrue(data.getLinkedCores(buttonSerial).isEmpty());
		assertTrue(data.getLinkedButtons(coreSerial).isEmpty());
	}

	/**
	 * 已退役序列号应被分配器跳过。
	 */
	@Test
	void allocateSerialShouldSkipRetired() {
		LinkSavedData data = new LinkSavedData();
		data.retireNode(LinkNodeType.CORE, 1L);

		long coreSerial = data.allocateSerial(LinkNodeType.CORE);

		assertEquals(2L, coreSerial);
		assertFalse(data.isSerialRetired(LinkNodeType.CORE, coreSerial));
	}

	/**
	 * 清链路应同步清理双向索引。
	 */
	@Test
	void clearLinksShouldRemoveBothSides() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long buttonA = data.allocateSerial(LinkNodeType.BUTTON);
		long buttonB = data.allocateSerial(LinkNodeType.BUTTON);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(10, 64, 10), LinkNodeType.CORE);
		data.registerNode(buttonA, Level.OVERWORLD, new BlockPos(11, 64, 10), LinkNodeType.BUTTON);
		data.registerNode(buttonB, Level.OVERWORLD, new BlockPos(12, 64, 10), LinkNodeType.BUTTON);
		data.toggleLink(buttonA, coreSerial);
		data.toggleLink(buttonB, coreSerial);

		int removed = data.clearLinksForNode(LinkNodeType.CORE, coreSerial);

		assertEquals(2, removed);
		assertTrue(data.getLinkedButtons(coreSerial).isEmpty());
		assertTrue(data.getLinkedCores(buttonA).isEmpty());
		assertTrue(data.getLinkedCores(buttonB).isEmpty());
	}

	/**
	 * 审计快照应识别缺失端点并统计数量。
	 */
	@Test
	void auditSnapshotShouldCountMissingEndpoints() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long buttonSerial = data.allocateSerial(LinkNodeType.BUTTON);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(20, 64, 20), LinkNodeType.CORE);
		data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(21, 64, 20), LinkNodeType.BUTTON);
		data.toggleLink(buttonSerial, coreSerial);

		LinkSavedData.AuditSnapshot healthy = data.createAuditSnapshot();
		assertEquals(1, healthy.onlineCoreNodes());
		assertEquals(1, healthy.onlineButtonNodes());
		assertEquals(1, healthy.totalLinks());
		assertEquals(0, healthy.linksWithMissingEndpoint());
		assertEquals(1, healthy.linkedButtonSerialCount());
		assertEquals(1, healthy.linkedCoreSerialCount());

		data.removeNode(LinkNodeType.CORE, coreSerial);
		LinkSavedData.AuditSnapshot missing = data.createAuditSnapshot();
		assertEquals(0, missing.onlineCoreNodes());
		assertEquals(1, missing.onlineButtonNodes());
		assertEquals(1, missing.totalLinks());
		assertEquals(1, missing.linksWithMissingEndpoint());
		assertEquals(1, missing.linkedButtonSerialCount());
		assertEquals(1, missing.linkedCoreSerialCount());
	}
}
