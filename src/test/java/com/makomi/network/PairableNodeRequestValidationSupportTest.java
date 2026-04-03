package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 可配对节点网络定位校验支撑的纯逻辑回归测试。
 */
@Tag("stable-core")
class PairableNodeRequestValidationSupportTest {
	/**
	 * 距离落在阈值内时应允许继续按该目标处理。
	 */
	@Test
	void isWithinInteractionDistanceShouldAcceptTargetInsideRadius() {
		assertTrue(PairableNodeRequestValidationSupport.isWithinInteractionDistance(0.5D, 64.5D, 0.5D, new BlockPos(0, 64, 0), 8));
	}

	/**
	 * 距离超过阈值时应拒绝继续处理目标。
	 */
	@Test
	void isWithinInteractionDistanceShouldRejectTargetOutsideRadius() {
		assertFalse(
			PairableNodeRequestValidationSupport.isWithinInteractionDistance(0.5D, 64.5D, 0.5D, new BlockPos(9, 64, 0), 8)
		);
	}

	/**
	 * 类型与序号必须同时匹配才允许通过身份校验。
	 */
	@Test
	void matchesExpectedNodeIdentityShouldRequireTypeAndSerialBothMatch() {
		assertTrue(
			PairableNodeRequestValidationSupport.matchesExpectedNodeIdentity(
				LinkNodeType.TRIGGER_SOURCE,
				12L,
				LinkNodeType.TRIGGER_SOURCE,
				12L
			)
		);
		assertFalse(
			PairableNodeRequestValidationSupport.matchesExpectedNodeIdentity(
				LinkNodeType.CORE,
				12L,
				LinkNodeType.TRIGGER_SOURCE,
				12L
			)
		);
		assertFalse(
			PairableNodeRequestValidationSupport.matchesExpectedNodeIdentity(
				LinkNodeType.TRIGGER_SOURCE,
				13L,
				LinkNodeType.TRIGGER_SOURCE,
				12L
			)
		);
	}
}
