package com.makomi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 展示文本列表格式化工具测试。
 */
@Tag("stable-core")
class DisplayTextListFormatUtilTest {
	/**
	 * 展示文本应按 `/` 连接，并在超长时追加省略数量。
	 */
	@Test
	void buildTextShouldJoinAndTruncateDisplayTexts() {
		List<String> displayTexts = List.of("门厅(#3)", "#5", "中控(#9)");

		assertEquals("门厅(#3)/#5/中控(#9)", DisplayTextListFormatUtil.buildText(displayTexts, 64));
		assertEquals("门厅(#3)(+2)", DisplayTextListFormatUtil.buildText(displayTexts, 12));
		assertEquals("(+3)", DisplayTextListFormatUtil.buildText(displayTexts, 4));
	}

	/**
	 * 空展示文本列表应显示占位符。
	 */
	@Test
	void buildTextShouldReturnDashForEmptyDisplayTexts() {
		assertEquals("-", DisplayTextListFormatUtil.buildText(List.of(), 16));
		assertEquals("-", DisplayTextListFormatUtil.buildText(List.of(" ", ""), 16));
	}

	/**
	 * 换行构造应按宽度拆分，并保留剩余数量。
	 */
	@Test
	void buildWrappedLinesShouldSplitByMeasure() {
		List<String> lines = DisplayTextListFormatUtil.buildWrappedLines(
			List.of("门厅(#3)", "#5", "中控(#9)"),
			8,
			2,
			String::length
		);

		assertEquals(List.of("门厅(#3)", "#5(+1)"), lines);
	}
}
