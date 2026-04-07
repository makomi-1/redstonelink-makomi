package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 的稳定核心行为测试。
 */
@Tag("stable-core")
class LinkSavedDataTest {
	private static final ResourceKey<Level> DIMENSION = Level.OVERWORLD;

	/**
	 * CORE 与 TRIGGER_SOURCE 应维护各自独立的序列号计数器。
	 */
	@Test
	void allocateSerialShouldUseIndependentCountersByType() {
		LinkSavedData data = new LinkSavedData();

		long core1 = data.allocateSerial(LinkNodeType.CORE);
		long core2 = data.allocateSerial(LinkNodeType.CORE);
		long triggerSource1 = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
		long triggerSource2 = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);

		assertEquals(1L, core1);
		assertEquals(2L, core2);
		assertEquals(1L, triggerSource1);
		assertEquals(2L, triggerSource2);
		assertTrue(data.isSerialAllocated(LinkNodeType.CORE, core1));
		assertTrue(data.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSource1));
	}

	/**
	 * 放置序列号解析应处理“同号异坐标冲突”并重分配。
	 */
	@Test
	void resolvePlacementSerialShouldReallocateWhenPositionConflicts() {
		LinkSavedData data = new LinkSavedData();
		long serial = data.allocateSerial(LinkNodeType.CORE);
		BlockPos sourcePos = new BlockPos(10, 64, 10);
		BlockPos conflictPos = new BlockPos(11, 64, 10);

		data.registerNode(serial, DIMENSION, sourcePos, LinkNodeType.CORE);

		long keepSerial = data.resolvePlacementSerial(LinkNodeType.CORE, serial, DIMENSION, sourcePos);
		long reallocatedSerial = data.resolvePlacementSerial(LinkNodeType.CORE, serial, DIMENSION, conflictPos);

		assertEquals(serial, keepSerial);
		assertNotEquals(serial, reallocatedSerial);
		assertTrue(data.isSerialAllocated(LinkNodeType.CORE, reallocatedSerial));
	}

	/**
	 * 已退役序列号在放置时应被拒绝复用并重分配。
	 */
	@Test
	void resolvePlacementSerialShouldRejectRetiredSerial() {
		LinkSavedData data = new LinkSavedData();
		long retired = data.allocateSerial(LinkNodeType.CORE);
		data.retireNode(LinkNodeType.CORE, retired);

		long resolved = data.resolvePlacementSerial(LinkNodeType.CORE, retired, DIMENSION, BlockPos.ZERO);

		assertNotEquals(retired, resolved);
		assertTrue(data.isSerialAllocated(LinkNodeType.CORE, resolved));
		assertFalse(data.isSerialRetired(LinkNodeType.CORE, resolved));
		assertTrue(data.isSerialRetired(LinkNodeType.CORE, retired));
	}

	/**
	 * 链路切换应保持 triggerSource->core、core->triggerSource 双向索引一致。
	 */
	@Test
	void toggleTriggerSourceCoreLinkShouldMaintainBidirectionalIndex() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 101L;
		long coreSerial = 202L;

		boolean enabled = data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
		boolean disabled = data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		assertTrue(enabled);
		assertFalse(disabled);
		assertEquals(0, data.getLinkedCoresByTriggerSource(triggerSourceSerial).size());
		assertEquals(0, data.getLinkedTriggerSourcesByCore(coreSerial).size());
	}

	/**
	 * 按节点类型读取对侧集合时，应保持与定向访问器一致。
	 */
	@Test
	void getLinkedPeersByNodeTypeShouldMatchDirectionalAccessors() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 1001L;
		long coreSerial = 2001L;

		assertTrue(data.addTriggerSourceCoreLink(triggerSourceSerial, coreSerial));
		assertEquals(Set.of(coreSerial), data.getLinkedPeersByNodeType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		assertEquals(Set.of(triggerSourceSerial), data.getLinkedPeersByNodeType(LinkNodeType.CORE, coreSerial));
	}

	/**
	 * 节点退役应清理关联链路并写入退役集合。
	 */
	@Test
	void retireNodeShouldClearLinksAndMarkRetired() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 301L;
		long coreSerial = 401L;

		data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.CORE, coreSerial);

		assertEquals(1, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialRetired(LinkNodeType.CORE, coreSerial));
		assertEquals(0, data.getLinkedCoresByTriggerSource(triggerSourceSerial).size());
		assertEquals(0, data.getLinkedTriggerSourcesByCore(coreSerial).size());
	}

	/**
	 * 按节点类型读取对侧集合时应返回稳定快照，避免后续链路变更回写到旧视图。
	 */
	@Test
	void getLinkedPeersByNodeTypeShouldReturnStableSnapshot() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 321L;
		long firstCoreSerial = 421L;
		long secondCoreSerial = 422L;

		data.toggleTriggerSourceCoreLink(triggerSourceSerial, firstCoreSerial);
		Set<Long> snapshot = data.getLinkedPeersByNodeType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);

		data.toggleTriggerSourceCoreLink(triggerSourceSerial, secondCoreSerial);
		data.toggleTriggerSourceCoreLink(triggerSourceSerial, firstCoreSerial);

		assertEquals(Set.of(firstCoreSerial), snapshot);
		assertEquals(Set.of(secondCoreSerial), data.getLinkedPeersByNodeType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.add(999L));
	}

	/**
	 * 真实图拓扑变更应推进 graph/source revision；无变化写入不应误递增。
	 */
	@Test
	void linkMutationsShouldAdvanceGraphAndSourceRevisionOnlyWhenChanged() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 330L;
		long coreSerial = 430L;

		assertEquals(0L, data.graphRevision());
		assertEquals(0L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));

		assertTrue(data.addTriggerSourceCoreLink(triggerSourceSerial, coreSerial));
		assertEquals(1L, data.graphRevision());
		assertEquals(1L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));

		assertFalse(data.addTriggerSourceCoreLink(triggerSourceSerial, coreSerial));
		assertEquals(1L, data.graphRevision());
		assertEquals(1L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));

		LinkSavedData.ReplaceLinksResult unchanged = data.replaceTriggerSourceTargets(triggerSourceSerial, Set.of(coreSerial));
		assertEquals(0, unchanged.changedCount());
		assertEquals(1L, data.graphRevision());
		assertEquals(1L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));

		assertTrue(data.removeTriggerSourceCoreLink(triggerSourceSerial, coreSerial));
		assertEquals(2L, data.graphRevision());
		assertEquals(2L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
	}

	/**
	 * 从 core 侧清理全部连接时，应只推进一次 graph revision，
	 * 并分别推进发生变化的 triggerSource revision。
	 */
	@Test
	void clearLinksForCoreShouldAdvanceAffectedSourceRevisions() {
		LinkSavedData data = new LinkSavedData();
		long coreSerial = 500L;
		long triggerSourceA = 601L;
		long triggerSourceB = 602L;

		data.toggleTriggerSourceCoreLink(triggerSourceA, coreSerial);
		data.toggleTriggerSourceCoreLink(triggerSourceB, coreSerial);
		assertEquals(2L, data.graphRevision());
		assertEquals(1L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceA));
		assertEquals(1L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceB));

		int removed = data.clearLinksForNode(LinkNodeType.CORE, coreSerial);

		assertEquals(2, removed);
		assertEquals(3L, data.graphRevision());
		assertEquals(2L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceA));
		assertEquals(2L, data.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceB));
		assertTrue(data.getLinkedTriggerSourcesByCore(coreSerial).isEmpty());
	}

	/**
	 * 审计快照应正确统计缺失端点链接数量。
	 */
	@Test
	void createAuditSnapshotShouldCountMissingEndpoints() {
		LinkSavedData data = new LinkSavedData();
		long onlineTriggerSource = 1L;
		long offlineTriggerSource = 2L;
		long onlineCore = 11L;
		long offlineCore = 12L;

		data.registerNode(onlineTriggerSource, DIMENSION, new BlockPos(0, 64, 0), LinkNodeType.TRIGGER_SOURCE);
		data.registerNode(onlineCore, DIMENSION, new BlockPos(1, 64, 0), LinkNodeType.CORE);
		data.toggleTriggerSourceCoreLink(onlineTriggerSource, onlineCore);
		data.toggleTriggerSourceCoreLink(onlineTriggerSource, offlineCore);
		data.toggleTriggerSourceCoreLink(offlineTriggerSource, onlineCore);

		LinkSavedData.AuditSnapshot snapshot = data.createAuditSnapshot();
		assertEquals(1, snapshot.onlineTriggerSourceNodes());
		assertEquals(1, snapshot.onlineCoreNodes());
		assertEquals(3, snapshot.totalLinks());
		assertEquals(2, snapshot.linksWithMissingEndpoint());
		assertEquals(2, snapshot.linkedTriggerSourceSerialCount());
		assertEquals(2, snapshot.linkedCoreSerialCount());
	}

	/**
	 * 持久化输出应保持序列号升序，且不包含非正数。
	 */
	@Test
	void saveShouldWriteSortedSerialArrays() {
		LinkSavedData data = new LinkSavedData();
		data.markSerialAllocated(LinkNodeType.CORE, 9L);
		data.markSerialAllocated(LinkNodeType.CORE, 3L);
		data.markSerialAllocated(LinkNodeType.CORE, 6L);
		data.retireNode(LinkNodeType.CORE, 6L);

		CompoundTag tag = data.save(new CompoundTag(), null);
		assertTrue(tag.contains("allocatedCoreSerials", net.minecraft.nbt.Tag.TAG_LONG_ARRAY));
		assertTrue(tag.contains("retiredCoreSerials", net.minecraft.nbt.Tag.TAG_LONG_ARRAY));
		assertArrayEquals(new long[] { 3L, 6L, 9L }, tag.getLongArray("allocatedCoreSerials"));
		assertArrayEquals(new long[] { 6L }, tag.getLongArray("retiredCoreSerials"));
	}

	/**
	 * 活跃序列号集合应等于“已分配 - 已退役”，且返回值不可变。
	 */
	@Test
	void getActiveSerialsShouldExcludeRetiredAndBeImmutable() {
		LinkSavedData data = new LinkSavedData();
		data.markSerialAllocated(LinkNodeType.CORE, 1L);
		data.markSerialAllocated(LinkNodeType.CORE, 2L);
		data.markSerialAllocated(LinkNodeType.CORE, 3L);
		data.retireNode(LinkNodeType.CORE, 2L);

		var active = data.getActiveSerials(LinkNodeType.CORE);
		assertEquals(Set.of(1L, 3L), active);
		assertThrows(UnsupportedOperationException.class, () -> active.add(99L));
	}

	/**
	 * 退役 triggerSource 节点时应移除其全部链路并保持 core 侧索引一致。
	 */
	@Test
	void retireTriggerSourceShouldClearAllTriggerSourceSideLinks() {
		LinkSavedData data = new LinkSavedData();
		long triggerSource = 501L;
		long coreA = 601L;
		long coreB = 602L;

		data.toggleTriggerSourceCoreLink(triggerSource, coreA);
		data.toggleTriggerSourceCoreLink(triggerSource, coreB);
		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.TRIGGER_SOURCE, triggerSource);

		assertEquals(2, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, triggerSource));
		assertEquals(0, data.getLinkedCoresByTriggerSource(triggerSource).size());
		assertEquals(0, data.getLinkedTriggerSourcesByCore(coreA).size());
		assertEquals(0, data.getLinkedTriggerSourcesByCore(coreB).size());
	}

	/**
	 * clearLinksForNode 返回值应反映真实移除链路数量（triggerSource/core 两侧）。
	 */
	@Test
	void clearLinksForNodeShouldReturnRemovedCount() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceA = 701L;
		long triggerSourceB = 702L;
		long core = 801L;

		data.toggleTriggerSourceCoreLink(triggerSourceA, core);
		data.toggleTriggerSourceCoreLink(triggerSourceB, core);

		int removedForCore = data.clearLinksForNode(LinkNodeType.CORE, core);
		assertEquals(2, removedForCore);
		assertEquals(0, data.getLinkedTriggerSourcesByCore(core).size());
		assertEquals(0, data.getLinkedCoresByTriggerSource(triggerSourceA).size());
		assertEquals(0, data.getLinkedCoresByTriggerSource(triggerSourceB).size());

		int removedAgain = data.clearLinksForNode(LinkNodeType.CORE, core);
		assertEquals(0, removedAgain);
	}

	/**
	 * triggerSource 卸载仅应移除在线坐标，不应丢失最近一次 sync replay 快照。
	 */
	@Test
	void removeNodeShouldKeepTriggerSourceReplaySnapshot() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 901L;
		data.registerNode(triggerSourceSerial, DIMENSION, new BlockPos(9, 64, 9), LinkNodeType.TRIGGER_SOURCE);
		data.putTriggerSourceReplaySyncSnapshot(triggerSourceSerial, EventMeta.of(123L, 0, 7L), 0);

		data.removeNode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);

		LinkSavedData.ReplaySyncSnapshotRecord snapshot = data
			.getTriggerSourceReplaySyncSnapshot(triggerSourceSerial)
			.orElseThrow();
		assertEquals(0, snapshot.signalStrength());
		assertEquals(EventMeta.of(123L, 0, 7L), snapshot.eventMeta());
	}

	/**
	 * triggerSource 退役后应清理持久化 replay 快照，避免序号残留旧态。
	 */
	@Test
	void retireNodeShouldClearTriggerSourceReplaySnapshot() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 902L;
		data.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);
		data.putTriggerSourceReplaySyncSnapshot(triggerSourceSerial, EventMeta.of(456L, 0, 8L), 15);

		data.retireNode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);

		assertTrue(data.getTriggerSourceReplaySyncSnapshot(triggerSourceSerial).isEmpty());
	}
}
