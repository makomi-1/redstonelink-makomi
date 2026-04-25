package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器待退役匹配回归测试。
 */
@Tag("stable-core")
class LinkNodeRetireMatchSupportTest {
	/**
	 * 转发器统一序号应同时匹配同号 `triggerSource/core` 两类待退役键。
	 */
	@Test
	void shouldDualMatchRepeaterPendingKeyShouldTreatRepeaterAsDualIdentity() {
		assertTrue(
			LinkNodeRetireMatchSupport.shouldDualMatchRepeaterPendingKey(
				41L,
				new LinkNodeRetireEvents.PendingKey(LinkNodeType.TRIGGER_SOURCE, 41L)
			)
		);
		assertTrue(
			LinkNodeRetireMatchSupport.shouldDualMatchRepeaterPendingKey(41L, new LinkNodeRetireEvents.PendingKey(LinkNodeType.CORE, 41L))
		);
		assertFalse(
			LinkNodeRetireMatchSupport.shouldDualMatchRepeaterPendingKey(
				41L,
				new LinkNodeRetireEvents.PendingKey(LinkNodeType.TRIGGER_SOURCE, 42L)
			)
		);
	}
}
