package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkOccSupport 的 OCC 基线包装测试。
 */
@Tag("stable-core")
class LinkOccSupportTest {
	/**
	 * `core` 视角在期望 revision 与当前真值一致时不应误报冲突。
	 */
	@Test
	void resolveCoreConflictShouldAcceptMatchingCoreRevision() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 11L;
		long coreSerial = 21L;
		data.addTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		assertEquals(1L, data.coreRevision(coreSerial));
		assertNull(LinkOccSupport.resolveCoreConflict(data, coreSerial, 1L));
	}

	/**
	 * `core` 冲突结果应保留真实 `expectedCoreRevision`，未使用的 source revision 固定为 0。
	 */
	@Test
	void resolveCoreConflictShouldExposeExpectedCoreRevisionInConflictResult() {
		LinkSavedData data = new LinkSavedData();
		long triggerSourceSerial = 12L;
		long coreSerial = 22L;
		data.addTriggerSourceCoreLink(triggerSourceSerial, coreSerial);

		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveCoreConflict(data, coreSerial, 7L);

		assertNotNull(conflict);
		assertEquals(LinkNodeType.CORE, conflict.targetNodeType());
		assertEquals(7L, conflict.expectedCoreRevision());
		assertEquals(0L, conflict.expectedSourceRevision());
		assertEquals(1L, conflict.currentCoreRevision());
		assertEquals("message.redstonelink.pairing.conflict.core_revision", conflict.messageKey());
	}
}
