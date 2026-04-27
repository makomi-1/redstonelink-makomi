package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.testsupport.TestMinecraftSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 序列号治理与审计行为回归测试。
 */
@Tag("stable-core")
class LinkSavedDataSerialGovernanceTest {
	static {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	/**
	 * 退役节点应清理链接并记录退役状态。
	 */
	@Test
	void retireNodeShouldClearLinksAndMarkRetired() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long triggerSourceSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(5, 64, 5), LinkNodeType.CORE);
		data.registerNode(triggerSourceSerial, Level.OVERWORLD, new BlockPos(6, 64, 5), LinkNodeType.TRIGGER_SOURCE);
		data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);

		assertTrue(result.nodeRemoved());
		assertEquals(1, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		assertTrue(data.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceSerial).isEmpty());
		assertTrue(data.getLinkedTriggerSourcesByCore(coreSerial).isEmpty());
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
		long triggerSourceA = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		long triggerSourceB = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(10, 64, 10), LinkNodeType.CORE);
		data.registerNode(triggerSourceA, Level.OVERWORLD, new BlockPos(11, 64, 10), LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(triggerSourceB, Level.OVERWORLD, new BlockPos(12, 64, 10), LinkNodeType.TRIGGER_SOURCE);
		data.toggleTriggerSourceCoreLink(triggerSourceA, coreSerial);
		data.toggleTriggerSourceCoreLink(triggerSourceB, coreSerial);

		int removed = data.clearLinksForNode(LinkNodeType.CORE, coreSerial);

		assertEquals(2, removed);
		assertTrue(data.getLinkedTriggerSourcesByCore(coreSerial).isEmpty());
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceA).isEmpty());
		assertTrue(data.getLinkedCoresByTriggerSource(triggerSourceB).isEmpty());
	}

	/**
	 * 审计快照应识别缺失端点并统计数量。
	 */
	@Test
	void auditSnapshotShouldCountMissingEndpoints() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = data.allocateSerial(LinkNodeType.CORE);
		long triggerSourceSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(20, 64, 20), LinkNodeType.CORE);
		data.registerNode(triggerSourceSerial, Level.OVERWORLD, new BlockPos(21, 64, 20), LinkNodeType.TRIGGER_SOURCE);
		data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		LinkSavedData.AuditSnapshot healthy = data.createAuditSnapshot();
		assertEquals(1, healthy.onlineCoreNodes());
		assertEquals(1, healthy.onlineTriggerSourceNodes());
		assertEquals(1, healthy.totalLinks());
		assertEquals(0, healthy.linksWithMissingEndpoint());
		assertEquals(1, healthy.linkedTriggerSourceSerialCount());
		assertEquals(1, healthy.linkedCoreSerialCount());

		data.removeNode(LinkNodeType.CORE, coreSerial);
		LinkSavedData.AuditSnapshot missing = data.createAuditSnapshot();
		assertEquals(0, missing.onlineCoreNodes());
		assertEquals(1, missing.onlineTriggerSourceNodes());
		assertEquals(1, missing.totalLinks());
		assertEquals(1, missing.linksWithMissingEndpoint());
		assertEquals(1, missing.linkedTriggerSourceSerialCount());
		assertEquals(1, missing.linkedCoreSerialCount());
	}
}
