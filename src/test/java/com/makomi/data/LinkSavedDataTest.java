package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	 * CORE 与 BUTTON 应维护各自独立的序列号计数器。
	 */
	@Test
	void allocateSerialShouldUseIndependentCountersByType() {
		LinkSavedData data = new LinkSavedData();

		long core1 = data.allocateSerial(LinkNodeType.CORE);
		long core2 = data.allocateSerial(LinkNodeType.CORE);
		long button1 = data.allocateSerial(LinkNodeType.BUTTON);
		long button2 = data.allocateSerial(LinkNodeType.BUTTON);

		assertEquals(1L, core1);
		assertEquals(2L, core2);
		assertEquals(1L, button1);
		assertEquals(2L, button2);
		assertTrue(data.isSerialAllocated(LinkNodeType.CORE, core1));
		assertTrue(data.isSerialAllocated(LinkNodeType.BUTTON, button1));
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
	 * 链路切换应保持按钮->核心、核心->按钮双向索引一致。
	 */
	@Test
	void toggleLinkShouldMaintainBidirectionalIndex() {
		LinkSavedData data = new LinkSavedData();
		long buttonSerial = 101L;
		long coreSerial = 202L;

		boolean enabled = data.toggleLink(buttonSerial, coreSerial);
		boolean disabled = data.toggleLink(buttonSerial, coreSerial);

		assertTrue(enabled);
		assertFalse(disabled);
		assertEquals(0, data.getLinkedCores(buttonSerial).size());
		assertEquals(0, data.getLinkedButtons(coreSerial).size());
	}

	/**
	 * 来源语义别名方法应与历史命名方法保持一致行为。
	 */
	@Test
	void sourceSemanticAliasesShouldMatchLegacyAccessors() {
		LinkSavedData data = new LinkSavedData();
		long buttonSerial = 1001L;
		long coreSerial = 2001L;

		boolean linkedBySource = data.toggleLinkBySourceType(LinkNodeType.BUTTON, buttonSerial, coreSerial);
		assertTrue(linkedBySource);
		assertEquals(Set.of(coreSerial), data.getLinkedTargetsBySourceType(LinkNodeType.BUTTON, buttonSerial));
		assertEquals(Set.of(buttonSerial), data.getLinkedTargetsBySourceType(LinkNodeType.CORE, coreSerial));

		boolean toggledBackByCoreView = data.toggleLinkBySourceType(LinkNodeType.CORE, coreSerial, buttonSerial);
		assertFalse(toggledBackByCoreView);
		assertTrue(data.getLinkedCores(buttonSerial).isEmpty());
		assertTrue(data.getLinkedButtons(coreSerial).isEmpty());
	}

	/**
	 * 节点退役应清理关联链路并写入退役集合。
	 */
	@Test
	void retireNodeShouldClearLinksAndMarkRetired() {
		LinkSavedData data = new LinkSavedData();
		long buttonSerial = 301L;
		long coreSerial = 401L;

		data.toggleLink(buttonSerial, coreSerial);
		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.CORE, coreSerial);

		assertEquals(1, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialRetired(LinkNodeType.CORE, coreSerial));
		assertEquals(0, data.getLinkedCores(buttonSerial).size());
		assertEquals(0, data.getLinkedButtons(coreSerial).size());
	}

	/**
	 * 审计快照应正确统计缺失端点链接数量。
	 */
	@Test
	void createAuditSnapshotShouldCountMissingEndpoints() {
		LinkSavedData data = new LinkSavedData();
		long onlineButton = 1L;
		long offlineButton = 2L;
		long onlineCore = 11L;
		long offlineCore = 12L;

		data.registerNode(onlineButton, DIMENSION, new BlockPos(0, 64, 0), LinkNodeType.BUTTON);
		data.registerNode(onlineCore, DIMENSION, new BlockPos(1, 64, 0), LinkNodeType.CORE);
		data.toggleLink(onlineButton, onlineCore);
		data.toggleLink(onlineButton, offlineCore);
		data.toggleLink(offlineButton, onlineCore);

		LinkSavedData.AuditSnapshot snapshot = data.createAuditSnapshot();
		assertEquals(1, snapshot.onlineButtonNodes());
		assertEquals(1, snapshot.onlineCoreNodes());
		assertEquals(3, snapshot.totalLinks());
		assertEquals(2, snapshot.linksWithMissingEndpoint());
		assertEquals(2, snapshot.linkedButtonSerialCount());
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
	 * 退役按钮节点时应移除按钮侧全部链路并保持核心侧索引一致。
	 */
	@Test
	void retireButtonShouldClearAllButtonSideLinks() {
		LinkSavedData data = new LinkSavedData();
		long button = 501L;
		long coreA = 601L;
		long coreB = 602L;

		data.toggleLink(button, coreA);
		data.toggleLink(button, coreB);
		LinkSavedData.RetireResult result = data.retireNode(LinkNodeType.BUTTON, button);

		assertEquals(2, result.linksRemoved());
		assertTrue(result.retiredMarked());
		assertTrue(data.isSerialRetired(LinkNodeType.BUTTON, button));
		assertEquals(0, data.getLinkedCores(button).size());
		assertEquals(0, data.getLinkedButtons(coreA).size());
		assertEquals(0, data.getLinkedButtons(coreB).size());
	}

	/**
	 * clearLinksForNode 返回值应反映真实移除链路数量（按钮/核心两侧）。
	 */
	@Test
	void clearLinksForNodeShouldReturnRemovedCount() {
		LinkSavedData data = new LinkSavedData();
		long buttonA = 701L;
		long buttonB = 702L;
		long core = 801L;

		data.toggleLink(buttonA, core);
		data.toggleLink(buttonB, core);

		int removedForCore = data.clearLinksForNode(LinkNodeType.CORE, core);
		assertEquals(2, removedForCore);
		assertEquals(0, data.getLinkedButtons(core).size());
		assertEquals(0, data.getLinkedCores(buttonA).size());
		assertEquals(0, data.getLinkedCores(buttonB).size());

		int removedAgain = data.clearLinksForNode(LinkNodeType.CORE, core);
		assertEquals(0, removedAgain);
	}
}
