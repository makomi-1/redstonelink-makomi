package com.makomi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 序号结构化展示工具测试。
 */
@Tag("stable-core")
class SerialDisplayFormatUtilTest {
	/**
	 * 连续序号应压缩为区间分段。
	 */
	@Test
	void buildExpressionShouldCompressContinuousRanges() {
		SerialDisplayFormatUtil.StructuredExpression expression = SerialDisplayFormatUtil.buildExpression(
			List.of(3L, 2L, 1L, 7L, 8L, 9L, 15L, 5L)
		);

		assertEquals(List.of("1:3", "5", "7:9", "15"), expression.segments());
		assertEquals(List.of(3, 1, 3, 1), expression.segmentCounts());
		assertEquals(8, expression.totalSerialCount());
		assertEquals("1:3/5/7:9/15", expression.joinAll());
	}

	/**
	 * 文本超长时应按分段截断并追加省略数量。
	 */
	@Test
	void buildTextShouldTruncateAndAppendRemainingCount() {
		List<Long> serials = List.of(1L, 2L, 3L, 5L, 7L, 8L, 9L, 15L);
		assertEquals("1:3/5/7:9/15", SerialDisplayFormatUtil.buildText(serials, 64));
		assertEquals("1:3(+5)", SerialDisplayFormatUtil.buildText(serials, 8));
		assertEquals("(+8)", SerialDisplayFormatUtil.buildText(serials, 4));
	}

	/**
	 * 空或非法输入应返回占位符。
	 */
	@Test
	void buildTextShouldReturnDashForEmptyInput() {
		assertEquals("-", SerialDisplayFormatUtil.buildText(List.of(), 16));
		assertEquals("-", SerialDisplayFormatUtil.buildText(List.of(-1L, 0L), 16));
		assertEquals("-", SerialDisplayFormatUtil.buildText(List.of(1L, 2L, 3L), 0));
		assertTrue(SerialDisplayFormatUtil.buildExpression(List.of(-1L, 0L)).isEmpty());
	}
}
