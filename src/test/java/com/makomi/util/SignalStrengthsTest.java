package com.makomi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 信号强度归一化工具测试。
 */
@Tag("stable-core")
class SignalStrengthsTest {
	/**
	 * 负值应归一化为 0。
	 */
	@Test
	void clampShouldReturnZeroForNegativeInput() {
		assertEquals(0, SignalStrengths.clamp(-1));
		assertEquals(0, SignalStrengths.clamp(-999));
	}

	/**
	 * 合法范围值应保持不变。
	 */
	@Test
	void clampShouldKeepInRangeValues() {
		assertEquals(0, SignalStrengths.clamp(0));
		assertEquals(7, SignalStrengths.clamp(7));
		assertEquals(15, SignalStrengths.clamp(15));
	}

	/**
	 * 超上限值应归一化为 15。
	 */
	@Test
	void clampShouldReturnFifteenForOverflowInput() {
		assertEquals(15, SignalStrengths.clamp(16));
		assertEquals(15, SignalStrengths.clamp(1024));
	}
}
