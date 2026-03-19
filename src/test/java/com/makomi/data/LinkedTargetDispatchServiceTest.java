package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkedTargetDispatchService 语义分发契约测试。
 */
@Tag("stable-core")
class LinkedTargetDispatchServiceTest {
	/**
	 * activationMode 为空时应走空摘要兜底。
	 */
	@Test
	void dispatchActivationShouldReturnEmptySummaryWhenModeIsNull() {
		LinkedTargetDispatchService.DispatchSummary summary = LinkedTargetDispatchService.dispatchActivation(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(10L, 20L),
			null
		);

		assertEquals(2, summary.totalTargets());
		assertEquals(0, summary.handledCount());
		assertFalse(summary.hasCrossChunkHandled());
	}

	/**
	 * 入参非法时应返回空摘要。
	 */
	@Test
	void dispatchShouldRejectInvalidArguments() {
		LinkedTargetDispatchService.DispatchSummary nullLevel = LinkedTargetDispatchService.dispatchActivation(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(100L),
			ActivationMode.TOGGLE
		);
		assertEquals(0, nullLevel.handledCount());

		LinkedTargetDispatchService.DispatchSummary emptyTargets = LinkedTargetDispatchService.dispatchSyncSignal(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(),
			15
		);
		assertEquals(0, emptyTargets.totalTargets());
	}

	/**
	 * 跨区块提示构建在空输入与无接管时应返回空列表。
	 */
	@Test
	void buildCrossChunkNotifyMessagesShouldReturnEmptyWhenNoCrossChunkHandled() {
		assertTrue(LinkedTargetDispatchService.buildCrossChunkNotifyMessages(null).isEmpty());

		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			2,
			0,
			List.of(),
			List.of()
		);
		assertTrue(LinkedTargetDispatchService.buildCrossChunkNotifyMessages(summary).isEmpty());
	}

	/**
	 * 目标文本格式化应按 displayLimit 截断并附加剩余数量。
	 */
	@Test
	void formatNotifyTargetsShouldApplyDisplayLimit() throws Exception {
		Method formatMethod = LinkedTargetDispatchService.class.getDeclaredMethod(
			"formatNotifyTargets",
			LinkNodeType.class,
			List.class,
			int.class
		);
		formatMethod.setAccessible(true);

		assertEquals("-", formatMethod.invoke(null, LinkNodeType.CORE, List.of(), 3));
		assertEquals(
			"core:4, core:5 (+1)",
			formatMethod.invoke(null, LinkNodeType.CORE, List.of(4L, 5L, 6L), 2)
		);
		assertEquals(
			"core:4 (+2)",
			formatMethod.invoke(null, LinkNodeType.CORE, List.of(4L, 5L, 6L), 0)
		);
	}

	/**
	 * 序号快照应按升序返回不可变列表。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void immutableSortedSerialsShouldReturnSortedImmutableList() throws Exception {
		Method sortMethod = LinkedTargetDispatchService.class.getDeclaredMethod("immutableSortedSerials", List.class);
		sortMethod.setAccessible(true);

		List<Long> sorted = (List<Long>) sortMethod.invoke(null, List.of(9L, 1L, 5L));
		assertEquals(List.of(1L, 5L, 9L), sorted);
		assertThrows(UnsupportedOperationException.class, () -> sorted.add(10L));
	}

	/**
	 * DispatchSummary 统计方法应正确反映跨区块接管数。
	 */
	@Test
	void dispatchSummaryShouldReportCrossChunkHandledCount() {
		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			3,
			1,
			List.of(11L, 12L),
			List.of(21L)
		);
		assertEquals(3, summary.crossChunkHandledCount());
		assertTrue(summary.hasCrossChunkHandled());
	}
}
