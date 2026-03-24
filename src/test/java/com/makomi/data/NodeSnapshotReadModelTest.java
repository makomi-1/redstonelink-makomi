package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 节点读模型 DTO 契约测试。
 */
@Tag("stable-core")
class NodeSnapshotReadModelTest {
	/**
	 * 身份快照应归一化序号与坐标对象。
	 */
	@Test
	void identitySnapshotShouldNormalizeValues() {
		NodeIdentitySnapshot snapshot = new NodeIdentitySnapshot(LinkNodeType.TRIGGER_SOURCE, -5L, true, false, true, null, null);

		assertEquals(LinkNodeType.TRIGGER_SOURCE, snapshot.nodeType());
		assertEquals(0L, snapshot.serial());
		assertTrue(snapshot.allocated());
		assertFalse(snapshot.retired());
		assertTrue(snapshot.online());
	}

	/**
	 * 运行态快照应通过 identity 暴露节点基础字段。
	 */
	@Test
	void runtimeSnapshotShouldDelegateIdentityFields() {
		NodeIdentitySnapshot identity = new NodeIdentitySnapshot(LinkNodeType.CORE, 88L, true, false, true, null, null);
		NodeRuntimeSnapshot snapshot = new NodeRuntimeSnapshot(
			TraceNodeKind.CORE,
			identity,
			123L,
			0,
			true,
			15,
			15,
			"sync",
			"sync",
			15,
			List.of(1L, 2L),
			0,
			0
		);

		assertEquals(LinkNodeType.CORE, snapshot.nodeType());
		assertEquals(88L, snapshot.serial());
		assertTrue(snapshot.allocated());
		assertFalse(snapshot.retired());
		assertTrue(snapshot.online());
		assertEquals(2, snapshot.maxSourceCount());
	}

	/**
	 * 当前连接视图快照应自动去重、排序并保留遮罩标记。
	 */
	@Test
	void linksSnapshotShouldNormalizeVisibleTargets() {
		NodeIdentitySnapshot identity = new NodeIdentitySnapshot(LinkNodeType.TRIGGER_SOURCE, 9L, true, false, true, null, null);
		NodeLinksSnapshot snapshot = new NodeLinksSnapshot(identity, List.of(5L, 3L, 5L, -1L), true);

		assertNotNull(snapshot.sourceIdentity());
		assertEquals(List.of(3L, 5L), snapshot.visibleTargets());
		assertEquals(2, snapshot.visibleTargetCount());
		assertTrue(snapshot.masked());
	}
}
