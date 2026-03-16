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
		long buttonSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(5, 64, 5), LinkNodeType.CORE);
		data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(6, 64, 5), LinkNodeType.TRIGGER_SOURCE);
		data.toggleLink(buttonSerial, coreSerial);

		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.TRIGGER_SOURCE, buttonSerial);

		assertTrue(result.nodeRemoved());
		assertEquals(1, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, buttonSerial));
		assertTrue(data.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, buttonSerial));
		assertTrue(data.getLinkedCores(buttonSerial).isEmpty());
		assertTrue(data.getLinkedButtons(coreSerial).isEmpty());
	}

	/**
	 * 放置冲突时（同序列号不同坐标）应重分配序列号。
	 */
	@Test
	void resolvePlacementSerialShouldReassignOnPositionConflict() {
		LinkSavedData data = new LinkSavedData();
		long preferred = data.allocateSerial(LinkNodeType.CORE);
		data.registerNode(preferred, Level.OVERWORLD, new BlockPos(100, 64, 100), LinkNodeType.CORE);

		long resolved = data.resolvePlacementSerial(LinkNodeType.CORE, preferred, Level.OVERWORLD, new BlockPos(101, 64, 100));

		assertTrue(resolved > 0L);
		assertFalse(resolved == preferred);
		assertTrue(data.isSerialAllocated(LinkNodeType.CORE, resolved));
	}

	/**
	 * 放置坐标一致时应复用首选序列号。
	 */
	@Test
	void resolvePlacementSerialShouldReuseWhenPositionMatches() {
		LinkSavedData data = new LinkSavedData();
		long preferred = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		BlockPos pos = new BlockPos(120, 64, 120);
		data.registerNode(preferred, Level.OVERWORLD, pos, LinkNodeType.TRIGGER_SOURCE);

		long resolved = data.resolvePlacementSerial(LinkNodeType.TRIGGER_SOURCE, preferred, Level.OVERWORLD, pos);

		assertEquals(preferred, resolved);
		assertTrue(data.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, resolved));
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
		long buttonA = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		long buttonB = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(10, 64, 10), LinkNodeType.CORE);
		data.registerNode(buttonA, Level.OVERWORLD, new BlockPos(11, 64, 10), LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(buttonB, Level.OVERWORLD, new BlockPos(12, 64, 10), LinkNodeType.TRIGGER_SOURCE);
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
		long buttonSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(20, 64, 20), LinkNodeType.CORE);
		data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(21, 64, 20), LinkNodeType.TRIGGER_SOURCE);
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
