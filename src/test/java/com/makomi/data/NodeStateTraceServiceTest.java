package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	 * traceKind 命令名应保持稳定，避免命令输出与挂载信息漂移。
	 */
	@Test
	void traceNodeKindCommandNamesShouldRemainStable() {
		assertEquals("core", TraceNodeKind.CORE.commandName());
		assertEquals("pulseTriggerSource", TraceNodeKind.PULSE_TRIGGER_SOURCE.commandName());
		assertEquals("toggleTriggerSource", TraceNodeKind.TOGGLE_TRIGGER_SOURCE.commandName());
		assertEquals("syncTriggerSource", TraceNodeKind.SYNC_TRIGGER_SOURCE.commandName());
	}

	/**
	 * 批量延迟分析应能识别出向后平移的命中窗口。
	 */
	@Test
	void analyzeLatencySamplesShouldReportMatchedDelay() {
		List<NodeRuntimeSnapshot> samples = List.of(
			snapshot(8L, 0),
			snapshot(9L, 0),
			snapshot(10L, 0),
			snapshot(11L, 0),
			snapshot(12L, 15),
			snapshot(13L, 15),
			snapshot(14L, 0),
			snapshot(15L, 0)
		);

		NodeStateTraceService.TraceLatencySampleResult result = NodeStateTraceService.analyzeLatencySamples(
			LinkNodeType.CORE,
			101L,
			1,
			samples,
			10L,
			List.of(15, 15, 0, 0),
			null
		);

		assertTrue(result.mounted());
		assertTrue(result.matched());
		assertEquals(12L, result.actualStartTick());
		assertEquals(2L, result.inputDelayTicks());
		assertEquals("matched", result.reason());
	}

	/**
	 * 样本不足时应显式返回 `insufficient_samples`，避免误判为零延迟。
	 */
	@Test
	void analyzeLatencySamplesShouldReportInsufficientSamplesWhenWindowIncomplete() {
		List<NodeRuntimeSnapshot> samples = List.of(
			snapshot(20L, 0),
			snapshot(21L, 15),
			snapshot(22L, 15)
		);

		NodeStateTraceService.TraceLatencySampleResult result = NodeStateTraceService.analyzeLatencySamples(
			LinkNodeType.CORE,
			202L,
			1,
			samples,
			21L,
			List.of(15, 15, 0, 0),
			null
		);

		assertTrue(result.mounted());
		assertFalse(result.matched());
		assertEquals("insufficient_samples", result.reason());
	}

	/**
	 * 连续方波在超出首窗口搜索上界后，应拒绝回落到后续重复周期。
	 */
	@Test
	void analyzeLatencySamplesShouldRejectRepeatedCycleOutsideLatestExpectedStartTick() {
		List<NodeRuntimeSnapshot> samples = List.of(
			snapshot(18L, 15),
			snapshot(19L, 15),
			snapshot(20L, 0),
			snapshot(21L, 0),
			snapshot(22L, 15),
			snapshot(23L, 15),
			snapshot(24L, 0),
			snapshot(25L, 0)
		);

		NodeStateTraceService.TraceLatencySampleResult result = NodeStateTraceService.analyzeLatencySamples(
			LinkNodeType.CORE,
			303L,
			1,
			samples,
			10L,
			List.of(15, 15, 0, 0),
			13L
		);

		assertTrue(result.mounted());
		assertFalse(result.matched());
		assertEquals("search_window_exhausted", result.reason());
	}

	/**
	 * 构造最小快照样本，避免测试绑定到具体方块实体实现。
	 */
	private static NodeRuntimeSnapshot snapshot(long sampleTick) {
		return snapshot(sampleTick, 15);
	}

	/**
	 * 构造指定功率的最小快照样本。
	 */
	private static NodeRuntimeSnapshot snapshot(long sampleTick, int power) {
		boolean active = power > 0;
		return new NodeRuntimeSnapshot(
			TraceNodeKind.CORE,
			new NodeIdentitySnapshot(LinkNodeType.CORE, 101L, true, false, true, null, null),
			sampleTick,
			0,
			active,
			power,
			power,
			"sync",
			"sync",
			power,
			List.of(1L, 2L),
			0,
			0
		);
	}
}
