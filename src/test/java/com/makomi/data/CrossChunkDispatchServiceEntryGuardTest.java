package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.makomi.block.entity.ActivationMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkDispatchService 公开入口参数守卫测试。
 */
@Tag("stable-core")
class CrossChunkDispatchServiceEntryGuardTest {
	/**
	 * queueActivation 在 activationMode 为空时应直接拒绝。
	 */
	@Test
	void queueActivationShouldRejectWhenActivationModeIsNull() {
		CrossChunkDispatchService.QueueResult result = CrossChunkDispatchService.queueActivation(
			null,
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			null
		);
		assertFalse(result.accepted());
		assertFalse(result.forceLoadPlanned());
	}

	/**
	 * queueActivation 在来源类型缺失时应直接拒绝。
	 */
	@Test
	void queueActivationShouldRejectWhenSourceTypeIsNull() {
		CrossChunkDispatchService.QueueResult result = CrossChunkDispatchService.queueActivation(
			null,
			null,
			null,
			1L,
			ActivationMode.TOGGLE
		);
		assertFalse(result.accepted());
		assertFalse(result.forceLoadPlanned());
	}

	/**
	 * queueSyncSignal 在关键入参缺失时应直接拒绝。
	 */
	@Test
	void queueSyncSignalShouldRejectWhenRequiredInputMissing() {
		CrossChunkDispatchService.QueueResult missingSourceType = CrossChunkDispatchService.queueSyncSignal(
			null,
			null,
			null,
			1L,
			15
		);
		assertFalse(missingSourceType.accepted());
		assertFalse(missingSourceType.forceLoadPlanned());

		CrossChunkDispatchService.QueueResult missingTargetNode = CrossChunkDispatchService.queueSyncSignal(
			null,
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			15
		);
		assertFalse(missingTargetNode.accepted());
		assertFalse(missingTargetNode.forceLoadPlanned());
	}
}
