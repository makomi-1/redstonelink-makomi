package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 发射器边沿模式的稳定契约测试。
 */
@Tag("stable-core")
class RedstoneLinkConfigEmitterEdgeModeTest {

	/**
	 * 非法或空配置应回退为 RISING。
	 */
	@Test
	void fromConfigValueShouldFallbackToRising() {
		assertEquals(RedstoneLinkConfig.EmitterEdgeMode.RISING, RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue(null));
		assertEquals(RedstoneLinkConfig.EmitterEdgeMode.RISING, RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue(""));
		assertEquals(RedstoneLinkConfig.EmitterEdgeMode.RISING, RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue("unknown"));
	}

	/**
	 * 合法配置值应正确映射到对应模式。
	 */
	@Test
	void fromConfigValueShouldMapKnownModes() {
		assertEquals(
			RedstoneLinkConfig.EmitterEdgeMode.FALLING,
			RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue("falling")
		);
		assertEquals(
			RedstoneLinkConfig.EmitterEdgeMode.BOTH,
			RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue(" both ")
		);
	}

	/**
	 * 上升沿模式仅在低到高时触发。
	 */
	@Test
	void risingModeShouldTriggerOnlyOnLowToHigh() {
		RedstoneLinkConfig.EmitterEdgeMode mode = RedstoneLinkConfig.EmitterEdgeMode.RISING;
		assertTrue(mode.shouldTrigger(false, true));
		assertFalse(mode.shouldTrigger(true, false));
		assertFalse(mode.shouldTrigger(false, false));
		assertFalse(mode.shouldTrigger(true, true));
	}

	/**
	 * 下降沿模式仅在高到低时触发。
	 */
	@Test
	void fallingModeShouldTriggerOnlyOnHighToLow() {
		RedstoneLinkConfig.EmitterEdgeMode mode = RedstoneLinkConfig.EmitterEdgeMode.FALLING;
		assertFalse(mode.shouldTrigger(false, true));
		assertTrue(mode.shouldTrigger(true, false));
		assertFalse(mode.shouldTrigger(false, false));
		assertFalse(mode.shouldTrigger(true, true));
	}

	/**
	 * 双沿模式应在任意电平变化时触发。
	 */
	@Test
	void bothModeShouldTriggerOnAnyEdgeChange() {
		RedstoneLinkConfig.EmitterEdgeMode mode = RedstoneLinkConfig.EmitterEdgeMode.BOTH;
		assertTrue(mode.shouldTrigger(false, true));
		assertTrue(mode.shouldTrigger(true, false));
		assertFalse(mode.shouldTrigger(false, false));
		assertFalse(mode.shouldTrigger(true, true));
	}
}
