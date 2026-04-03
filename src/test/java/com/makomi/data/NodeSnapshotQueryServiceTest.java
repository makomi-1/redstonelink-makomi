package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * NodeSnapshotQueryService 物品快照查询契约测试。
 */
@Tag("stable-core")
class NodeSnapshotQueryServiceTest {
	/**
	 * 物品快照应直接保留原始连接集合，不走隐私裁剪。
	 */
	@Test
	void buildItemSnapshotLinksShouldKeepUnfilteredVisibleTargets() {
		NodeLinksSnapshot snapshot = NodeSnapshotQueryService.buildItemSnapshotLinks(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			12L,
			new LinkedHashSet<>(List.of(7L, 3L, -1L))
		);

		assertEquals(LinkNodeType.TRIGGER_SOURCE, snapshot.sourceIdentity().nodeType());
		assertEquals(12L, snapshot.sourceIdentity().serial());
		assertEquals(java.util.List.of(3L, 7L), snapshot.visibleTargets());
		assertFalse(snapshot.masked());
		assertEquals(0L, snapshot.graphRevision());
		assertEquals(0L, snapshot.sourceRevision());
	}

	/**
	 * 物品快照在提供运行时存档上下文时，应透出当前 revision 基线。
	 */
	@Test
	void buildItemSnapshotLinksShouldExposeRevisionBaselineWhenSavedDataProvided() {
		LinkSavedData savedData = new LinkSavedData();
		savedData.toggleLink(15L, 31L);

		NodeLinksSnapshot snapshot = NodeSnapshotQueryService.buildItemSnapshotLinks(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			15L,
			new LinkedHashSet<>(List.of(31L)),
			savedData
		);

		assertEquals(1L, snapshot.graphRevision());
		assertEquals(1L, snapshot.sourceRevision());
	}
}
