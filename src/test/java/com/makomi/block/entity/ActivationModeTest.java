package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 激活模式解析契约测试。
 */
@Tag("stable-core")
class ActivationModeTest {

	/**
	 * fromName 应支持大小写无关解析。
	 */
	@Test
	void fromNameShouldBeCaseInsensitive() {
		assertEquals(ActivationMode.TOGGLE, ActivationMode.fromName("toggle"));
		assertEquals(ActivationMode.TOGGLE, ActivationMode.fromName("ToGgLe"));
		assertEquals(ActivationMode.PULSE, ActivationMode.fromName("pulse"));
		assertEquals(ActivationMode.PULSE, ActivationMode.fromName("PuLsE"));
	}

	/**
	 * fromName 对未知值应回退 TOGGLE。
	 */
	@Test
	void fromNameShouldFallbackToToggle() {
		assertEquals(ActivationMode.TOGGLE, ActivationMode.fromName(null));
		assertEquals(ActivationMode.TOGGLE, ActivationMode.fromName(""));
		assertEquals(ActivationMode.TOGGLE, ActivationMode.fromName("unknown"));
	}
}
