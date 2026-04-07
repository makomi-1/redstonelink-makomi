package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkOperationFeedback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * quick-link 服务端 revision 冲突判定的纯逻辑回归测试。
 */
@Tag("stable-core")
class QuickLinkNetworkServerHandlerSupportTest {
	/**
	 * `triggerSource` 目标在来源 revision 一致时不应返回冲突反馈。
	 */
	@Test
	void buildApplyRevisionConflictFeedbackShouldAllowMatchingTriggerSourceRevision() {
		assertNull(
			QuickLinkNetworkServerHandlerSupport.buildApplyRevisionConflictFeedback(
				LinkNodeType.TRIGGER_SOURCE,
				7L,
				99L,
				5L,
				101L,
				5L
			)
		);
	}

	/**
	 * `triggerSource` 目标应只比较 `expectedSourceRevision`。
	 */
	@Test
	void buildApplyRevisionConflictFeedbackShouldUseSourceRevisionForTriggerSource() {
		QuickLinkOperationFeedback feedback = QuickLinkNetworkServerHandlerSupport.buildApplyRevisionConflictFeedback(
			LinkNodeType.TRIGGER_SOURCE,
			7L,
			99L,
			4L,
			101L,
			6L
		);

		assertEquals("message.redstonelink.pairing.conflict.source_revision", feedback.messageKey());
		assertEquals("7", feedback.messageArgs().get(0));
		assertEquals("4", feedback.messageArgs().get(1));
		assertEquals("6", feedback.messageArgs().get(2));
	}

	/**
	 * `core` 目标应只比较 `expectedGraphRevision`。
	 */
	@Test
	void buildApplyRevisionConflictFeedbackShouldUseGraphRevisionForCore() {
		QuickLinkOperationFeedback feedback = QuickLinkNetworkServerHandlerSupport.buildApplyRevisionConflictFeedback(
			LinkNodeType.CORE,
			13L,
			8L,
			99L,
			11L,
			3L
		);

		assertEquals("message.redstonelink.pairing.conflict.graph_revision", feedback.messageKey());
		assertEquals("8", feedback.messageArgs().get(0));
		assertEquals("11", feedback.messageArgs().get(1));
	}

	/**
	 * `core` 目标在 graph revision 一致时，不应受 `sourceRevision` 差异影响。
	 */
	@Test
	void buildApplyRevisionConflictFeedbackShouldIgnoreSourceRevisionForCore() {
		assertNull(
			QuickLinkNetworkServerHandlerSupport.buildApplyRevisionConflictFeedback(
				LinkNodeType.CORE,
				13L,
				8L,
				99L,
				8L,
				3L
			)
		);
	}
}
