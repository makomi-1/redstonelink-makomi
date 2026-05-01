package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器延迟值对象契约测试。
 */
@Tag("stable-core")
class RepeaterDelayTest {
	/**
	 * 旧 `1tick/2tick` token 与新的自定义正整数 token 都应可解析。
	 */
	@Test
	void tryParseTokenShouldSupportLegacyAndCustomPositiveTicks() {
		assertEquals(RepeaterDelay.ONE_TICK, RepeaterDelay.tryParseToken("1tick").orElseThrow());
		assertEquals(RepeaterDelay.TWO_TICKS, RepeaterDelay.tryParseToken("2tick").orElseThrow());
		assertEquals(RepeaterDelay.ofTicks(5), RepeaterDelay.tryParseToken("5tick").orElseThrow());
	}

	/**
	 * 非法 token 仍应回退为 `1 tick`。
	 */
	@Test
	void fromTokenShouldFallbackToOneTickForInvalidToken() {
		assertEquals(RepeaterDelay.ONE_TICK, RepeaterDelay.fromToken(""));
		assertEquals(RepeaterDelay.ONE_TICK, RepeaterDelay.fromToken("0tick"));
		assertEquals(RepeaterDelay.ONE_TICK, RepeaterDelay.fromToken("abc"));
	}

	/**
	 * `>2 tick` 应标记为实验性配置。
	 */
	@Test
	void experimentalFlagShouldOnlyApplyAboveTwoTicks() {
		assertFalse(RepeaterDelay.ONE_TICK.experimental());
		assertFalse(RepeaterDelay.TWO_TICKS.experimental());
		assertTrue(RepeaterDelay.ofTicks(3).experimental());
	}
}
