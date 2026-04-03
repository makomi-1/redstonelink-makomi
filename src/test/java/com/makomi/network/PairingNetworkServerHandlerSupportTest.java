package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
