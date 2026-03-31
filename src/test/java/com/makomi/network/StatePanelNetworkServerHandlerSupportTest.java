package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeIdentitySnapshot;
import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.StatePanelToolData;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 状态面板服务端快照构建测试。
 */
@Tag("stable-core")
class StatePanelNetworkServerHandlerSupportTest {
	/**
	 * 不可读条目应统一降级为隐藏态，避免把真实状态字段泄露到客户端。
	 */
	@Test
	void buildSnapshotEntryShouldHideUnreadableState() {
		StatePanelToolData.SubscriptionEntry subscription = new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, 17L);

		StatePanelNetwork.StatePanelSnapshotEntry entry = StatePanelNetworkServerHandlerSupport.buildSnapshotEntry(
			subscription,
			false,
			null,
			null
		);

		assertFalse(entry.readable());
		assertFalse(entry.allocated());
		assertFalse(entry.retired());
		assertFalse(entry.online());
		assertFalse(entry.active());
	}

	/**
	 * 可读条目应保留真实身份与运行态字段。
	 */
	@Test
	void buildSnapshotEntryShouldKeepReadableState() {
		StatePanelToolData.SubscriptionEntry subscription = new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, 29L);
		NodeIdentitySnapshot identity = new NodeIdentitySnapshot(LinkNodeType.TRIGGER_SOURCE, 29L, true, false, true, null, null);
		NodeRuntimeSnapshot runtimeSnapshot = new NodeRuntimeSnapshot(
			TraceNodeKind.SYNC_TRIGGER_SOURCE,
			identity,
			20L,
			0,
			true,
			4,
			12,
			"sync",
			"sync",
			12,
			List.of(),
			0,
			0
		);

		StatePanelNetwork.StatePanelSnapshotEntry entry = StatePanelNetworkServerHandlerSupport.buildSnapshotEntry(
			subscription,
			true,
			identity,
			runtimeSnapshot
		);

		assertTrue(entry.readable());
		assertTrue(entry.allocated());
		assertFalse(entry.retired());
		assertTrue(entry.online());
		assertTrue(entry.active());
	}
}
