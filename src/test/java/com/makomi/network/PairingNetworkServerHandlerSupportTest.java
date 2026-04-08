package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 配对近外显服务端节流支撑的纯逻辑回归测试。
 */
@Tag("stable-core")
class PairingNetworkServerHandlerSupportTest {
	/**
	 * 请求落在最小 tick 间隔内时应视为节流窗口内。
	 */
	@Test
	void isRequestInsideThrottleWindowShouldRejectTicksInsideWindow() {
		assertTrue(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(100L, 104L, 5L));
	}

	/**
	 * 请求达到或超过最小 tick 间隔时应允许继续处理。
	 */
	@Test
	void isRequestInsideThrottleWindowShouldAllowTicksAtOrAfterWindowBoundary() {
		assertFalse(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(100L, 105L, 5L));
		assertFalse(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(100L, 106L, 5L));
	}

	/**
	 * 最小间隔非法时应按 1 tick 兜底，避免出现零窗口放行。
	 */
	@Test
	void isRequestInsideThrottleWindowShouldClampInvalidInterval() {
		assertTrue(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(100L, 100L, 0L));
		assertFalse(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(100L, 101L, 0L));
	}

	/**
	 * 新世界 tick 回绕时不应继续沿用旧世界残留节流窗口。
	 */
	@Test
	void isRequestInsideThrottleWindowShouldAllowWhenTickRewinds() {
		assertFalse(PairingNetworkServerHandlerSupport.isRequestInsideThrottleWindow(36_000L, 20L, 5L));
	}

	/**
	 * 当当前 tick 小于历史基线时，应识别为世界时钟已回绕。
	 */
	@Test
	void hasTickRewoundShouldDetectIntegratedServerWorldReset() {
		assertTrue(PairingNetworkServerHandlerSupport.hasTickRewound(36_000L, 0L));
		assertFalse(PairingNetworkServerHandlerSupport.hasTickRewound(100L, 100L));
		assertFalse(PairingNetworkServerHandlerSupport.hasTickRewound(100L, 105L));
	}

	/**
	 * expected revision 与当前真值一致时，不应判定为冲突。
	 */
	@Test
	void isRevisionMismatchShouldReturnFalseWhenExpectedMatchesCurrent() {
		assertFalse(PairingNetworkServerHandlerSupport.isRevisionMismatch(5L, 5L));
		assertFalse(PairingNetworkServerHandlerSupport.isRevisionMismatch(0L, 0L));
	}

	/**
	 * expected revision 与当前真值不一致时，应判定为冲突。
	 */
	@Test
	void isRevisionMismatchShouldReturnTrueWhenExpectedDiffers() {
		assertTrue(PairingNetworkServerHandlerSupport.isRevisionMismatch(4L, 5L));
		assertTrue(PairingNetworkServerHandlerSupport.isRevisionMismatch(0L, 3L));
	}

	/**
	 * core 视角编辑应只为真正发生变化的 triggerSource 生成新的 core 目标集合。
	 */
	@Test
	void buildChangedCoreTargetsByTriggerSourceShouldOnlyReturnChangedSources() {
		LinkedHashMap<Long, Set<Long>> changedTargets = PairingNetworkServerHandlerSupport.buildChangedCoreTargetsByTriggerSource(
			9L,
			Set.of(2L, 5L),
			List.of(2L, 7L),
			Map.of(2L, Set.of(9L, 11L), 5L, Set.of(9L), 7L, Set.of(12L))
		);

		assertEquals(2, changedTargets.size());
		assertEquals(Set.of(), changedTargets.get(5L));
		assertEquals(Set.of(9L, 12L), changedTargets.get(7L));
		assertFalse(changedTargets.containsKey(2L));
	}

	/**
	 * 当 core 成员关系已经对齐时，不应再生成任何正向写入计划。
	 */
	@Test
	void buildChangedCoreTargetsByTriggerSourceShouldReturnEmptyWhenAlreadyAligned() {
		LinkedHashMap<Long, Set<Long>> changedTargets = PairingNetworkServerHandlerSupport.buildChangedCoreTargetsByTriggerSource(
			15L,
			Set.of(3L, 4L),
			List.of(3L, 4L),
			Map.of(3L, Set.of(15L), 4L, Set.of(2L, 15L))
		);

		assertTrue(changedTargets.isEmpty());
	}
}
