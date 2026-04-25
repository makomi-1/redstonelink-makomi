package com.makomi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * “当前连接”摘要格式化工具测试。
 */
@Tag("stable-core")
class CurrentLinksDisplayFormatUtilTest {
	/**
	 * 连续纯序号应压缩为 `#A:#B`，别名节点应打断区间。
	 */
	@Test
	void buildExpressionShouldCompressPureSerialRangesAndBreakAtAlias() {
		CurrentLinksDisplayFormatUtil.StructuredExpression expression = CurrentLinksDisplayFormatUtil.buildExpression(
			List.of(1L, 2L, 3L, 4L, 5L, 7L, 8L),
			List.of("#1", "#2", "门厅(#3)", "#4", "#5", "#7", "#8")
		);

		assertEquals(List.of("#1:#2", "门厅(#3)", "#4:#5", "#7:#8"), expression.segments());
		assertEquals(List.of(2, 1, 2, 2), expression.segmentCounts());
		assertEquals(7, expression.totalSerialCount());
		assertEquals("#1:#2/门厅(#3)/#4:#5/#7:#8", expression.joinAll());
	}

	/**
	 * 展示文本缺失或数量不匹配时，应回退为纯 `#序号` 并继续区间压缩。
	 */
	@Test
	void buildTextShouldFallbackToSerialRangesWhenDisplayTextsMissing() {
		assertEquals(
			"#3:#5/#9",
			CurrentLinksDisplayFormatUtil.buildText(List.of(3L, 4L, 5L, 9L), List.of("门厅(#3)"), 64)
		);
	}

	/**
	 * 截断提示的剩余数量应按真实序号总量计算，而不是按分段数计算。
	 */
	@Test
	void buildTextShouldAppendRemainingSerialCount() {
		assertEquals(
			"#1:#3(+3)",
			CurrentLinksDisplayFormatUtil.buildText(
				List.of(1L, 2L, 3L, 4L, 5L, 6L),
				List.of("#1", "#2", "#3", "门厅(#4)", "#5", "#6"),
				9
			)
		);
	}

	/**
	 * 多行构建应优先保留结构化分段，并在尾行追加剩余数量。
	 */
	@Test
	void buildWrappedLinesShouldWrapStructuredSegments() {
		assertEquals(
			List.of("#1:#2", "门厅(#3)", "#4:#5(+2)"),
			CurrentLinksDisplayFormatUtil.buildWrappedLines(
				List.of(1L, 2L, 3L, 4L, 5L, 7L, 8L),
				List.of("#1", "#2", "门厅(#3)", "#4", "#5", "#7", "#8"),
				9,
				3,
				String::length
			)
		);
	}

	/**
	 * 空输入应返回占位符或空表达式。
	 */
	@Test
	void buildTextShouldReturnDashForEmptyInput() {
		assertEquals("-", CurrentLinksDisplayFormatUtil.buildText(List.of(), List.of(), 16));
		assertEquals("-", CurrentLinksDisplayFormatUtil.buildText(List.of(-1L, 0L), List.of("#1"), 16));
		assertTrue(CurrentLinksDisplayFormatUtil.buildExpression(List.of(), List.of()).isEmpty());
	}
}
