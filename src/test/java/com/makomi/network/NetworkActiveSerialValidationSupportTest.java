package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 网络侧 serial 活跃性校验测试。
 */
@Tag("stable-core")
class NetworkActiveSerialValidationSupportTest {
	/**
	 * 校验结果应区分“未分配”与“已退役”，以便保存链路复用统一反馈文案。
	 */
	@Test
	void collectInvalidSerialsShouldSeparateUnallocatedAndRetiredSerials() {
		LinkSavedData savedData = new LinkSavedData();
		savedData.markSerialAllocated(LinkNodeType.CORE, 1L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 2L);
		savedData.retireNode(LinkNodeType.CORE, 2L);

		NetworkActiveSerialValidationSupport.ValidationResult result = NetworkActiveSerialValidationSupport.collectInvalidSerials(
			savedData,
			LinkNodeType.CORE,
			List.of(1L, 2L, 3L)
		);

		assertEquals(List.of("3"), result.unallocatedSerials());
		assertEquals(List.of("2"), result.retiredSerials());
	}

	/**
	 * 不同节点类型应各自独立校验，不能把 triggerSource 的分配状态误用于 core。
	 */
	@Test
	void collectInvalidSerialsShouldRespectNodeTypeBoundary() {
		LinkSavedData savedData = new LinkSavedData();
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 9L);

		NetworkActiveSerialValidationSupport.ValidationResult coreResult = NetworkActiveSerialValidationSupport.collectInvalidSerials(
			savedData,
			LinkNodeType.CORE,
			List.of(9L)
		);
		NetworkActiveSerialValidationSupport.ValidationResult triggerSourceResult =
			NetworkActiveSerialValidationSupport.collectInvalidSerials(savedData, LinkNodeType.TRIGGER_SOURCE, List.of(9L));

		assertEquals(List.of("9"), coreResult.unallocatedSerials());
		assertTrue(coreResult.retiredSerials().isEmpty());
		assertTrue(triggerSourceResult.unallocatedSerials().isEmpty());
		assertTrue(triggerSourceResult.retiredSerials().isEmpty());
	}
}
