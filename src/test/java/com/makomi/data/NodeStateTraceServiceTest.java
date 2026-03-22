package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 节点状态采样 ring buffer 契约测试。
 */
@Tag("stable-core")
class NodeStateTraceServiceTest {
	/**
	 * 超出容量后应仅保留最新样本。
	 */
	@Test
	void traceBufferShouldKeepNewestSamplesWhenCapacityExceeded() {
		NodeStateTraceService.TraceBuffer buffer = new NodeStateTraceService.TraceBuffer(2);

		buffer.append(snapshot(10L));
		buffer.append(snapshot(11L));
		buffer.append(snapshot(12L));

		List<NodeRuntimeSnapshot> latest = buffer.readLatest(10);
		assertEquals(2, latest.size());
		assertEquals(12L, latest.get(0).sampleTick());
		assertEquals(11L, latest.get(1).sampleTick());
	}

	/**
	 * 缩容后应立即裁剪最旧样本。
	 */
	@Test
	void traceBufferShouldTrimOldestSamplesWhenCapacityShrinks() {
		NodeStateTraceService.TraceBuffer buffer = new NodeStateTraceService.TraceBuffer(4);

		buffer.append(snapshot(20L));
		buffer.append(snapshot(21L));
		buffer.append(snapshot(22L));
		buffer.append(snapshot(23L));
		buffer.setCapacity(2);

		List<NodeRuntimeSnapshot> latest = buffer.readLatest(10);
		assertEquals(2, latest.size());
		assertEquals(23L, latest.get(0).sampleTick());
		assertEquals(22L, latest.get(1).sampleTick());
	}

	/**
	 * 读取最近样本时应按最新优先返回。
	 */
	@Test
	void traceBufferReadLatestShouldReturnNewestFirst() {
		NodeStateTraceService.TraceBuffer buffer = new NodeStateTraceService.TraceBuffer(4);

		buffer.append(snapshot(30L));
		buffer.append(snapshot(31L));
		buffer.append(snapshot(32L));

		List<NodeRuntimeSnapshot> latest = buffer.readLatest(2);
		assertEquals(2, latest.size());
		assertEquals(32L, latest.get(0).sampleTick());
		assertEquals(31L, latest.get(1).sampleTick());
		assertTrue(buffer.latest().isPresent());
		assertEquals(32L, buffer.latest().orElseThrow().sampleTick());
	}

	/**
	 * 构造最小快照样本，避免测试绑定到具体方块实体实现。
	 */
	private static NodeRuntimeSnapshot snapshot(long sampleTick) {
		return new NodeRuntimeSnapshot(
			TraceNodeKind.CORE,
			LinkNodeType.CORE,
			101L,
			true,
			false,
			true,
			null,
			null,
			sampleTick,
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
	}
}
