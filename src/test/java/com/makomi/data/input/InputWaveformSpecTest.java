package com.makomi.data.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 输入波形规格测试。
 * <p>
 * 锁定首版最小输入器对方波与自定义序列的解析/采样语义。
 * </p>
 */
@Tag("stable-core")
class InputWaveformSpecTest {

	/**
	 * 验证方波会按周期、高电平持续时间和相位偏移稳定输出。
	 */
	@Test
	void squareWaveSamplesFollowPeriodAndPhase() {
		InputWaveformSpec spec = InputWaveformSpec.square(4, 2, 15, 0, 1);

		assertEquals(15, spec.sampleAt(0), "相位偏移后首个样本应处于高电平");
		assertEquals(0, spec.sampleAt(1), "进入低电平区间后应输出低功率");
		assertEquals(0, spec.sampleAt(2), "同一周期内低电平应保持稳定");
		assertEquals(15, spec.sampleAt(3), "跨周期后应重新回到高电平");
	}

	/**
	 * 验证位串/十六进制串格式会按字符逐位解析。
	 */
	@Test
	void customSequenceParsesHexStyleSequence() {
		assertIterableEquals(List.of(0, 1, 0, 1), InputWaveformSpec.parseCustomSequence("0101"));
	}

	/**
	 * 验证显式功率列表格式会保留原始功率序列。
	 */
	@Test
	void customSequenceParsesExplicitPowerList() {
		assertIterableEquals(List.of(15, 0, 7, 0), InputWaveformSpec.parseCustomSequence("15/0/7/0"));
	}

	/**
	 * 验证非法序列会被拒绝，避免命令层误接受脏输入。
	 */
	@Test
	void invalidCustomSequenceThrowsException() {
		assertThrows(IllegalArgumentException.class, () -> InputWaveformSpec.parseCustomSequence("17/0"));
	}
}
