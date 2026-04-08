package com.makomi.command.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.QuickLinkOperationFeedback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * bench OCC 命令稳定 summary 契约测试。
 */
@Tag("stable-core")
class BenchOccCommandRegistryTest {
	/**
	 * snapshot summary 应稳定输出 baseline 与当前目标数。
	 */
	@Test
	void buildSnapshotSummaryShouldExposeStableBaselineFields() {
		assertEquals(
			"[RedstoneLink/Bench] occ_snapshot type=triggerSource serial=7 graphRevision=11 sourceRevision=3 coreRevision=0 currentTargetCount=2",
			BenchOccCommandRegistry.buildSnapshotSummary(
				LinkNodeType.TRIGGER_SOURCE,
				7L,
				new LinkOccSupport.RevisionBaseline(11L, 3L, 0L),
				2
			)
		);
	}

	/**
	 * pairing conflict summary 应优先输出冲突中的 expected/current revision。
	 */
	@Test
	void buildPairingSummaryShouldPreferConflictRevisions() {
		LinkOccSupport.OccConflict conflict = new LinkOccSupport.OccConflict(
			LinkNodeType.CORE,
			15L,
			"message.redstonelink.pairing.conflict.core_revision",
			java.util.List.of("4", "5"),
			4L,
			0L,
			9L,
			1L,
			5L
		);

		String summary = BenchOccCommandRegistry.buildPairingSummary(
			"occ_pairing_submit",
			LinkNodeType.CORE,
			15L,
			0L,
			0L,
			new LinkOccSupport.RevisionBaseline(99L, 99L, 99L),
			conflict,
			1,
			0,
			"-",
			"conflict"
		);

		assertTrue(summary.contains("outcome=conflict"));
		assertTrue(summary.contains("expectedCoreRevision=4"));
		assertTrue(summary.contains("currentGraphRevision=9"));
		assertTrue(summary.contains("currentCoreRevision=5"));
		assertTrue(summary.contains("currentSourceRevision=1"));
		assertTrue(summary.contains("messageKey=message.redstonelink.pairing.conflict.core_revision"));
	}

	/**
	 * quick-link applied summary 应复用反馈 message key。
	 */
	@Test
	void buildQuickLinkSummaryShouldUseAppliedFeedbackMessageKey() {
		assertEquals(
			"[RedstoneLink/Bench] occ_quick_link_apply outcome=applied type=core serial=13 expectedCoreRevision=8 expectedSourceRevision=0 currentGraphRevision=9 currentSourceRevision=2 currentCoreRevision=4 affectedSourceCount=3 currentTargetCount=1 messageKey=message.redstonelink.quick_link.apply.done.core",
			BenchOccCommandRegistry.buildQuickLinkSummary(
				LinkNodeType.CORE,
				13L,
				8L,
				0L,
				new LinkOccSupport.RevisionBaseline(9L, 2L, 4L),
				null,
				1,
				3,
				QuickLinkOperationFeedback.success("message.redstonelink.quick_link.apply.done.core", "3", "13"),
				"applied"
			)
		);
	}
}
