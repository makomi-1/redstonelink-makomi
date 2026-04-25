package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器首页摘要 tooltip 契约测试。
 */
@Tag("client")
class RepeaterEditorScreenTest {

	/**
	 * tooltip 应优先展示服务端归一后的别名文本列表，并按宽度追加剩余数量。
	 */
	@Test
	void buildSummaryTooltipTextsShouldPreferDisplayTexts() {
		List<String> tooltipLines = RepeaterEditorScreen.buildSummaryTooltipTexts(
			"3/5/9/",
			List.of("门厅(#3)", "#5", "中控(#9)"),
			8,
			2,
			String::length
		);

		assertEquals(List.of("门厅(#3)", "#5(+1)"), tooltipLines);
	}

	/**
	 * 缺少展示文本时，tooltip 应回退到原始序号表达式，并保持 `N`/`A:B` 分段语义。
	 */
	@Test
	void buildSummaryTooltipTextsShouldFallbackToSerialExpressionTokens() {
		List<String> tooltipLines = RepeaterEditorScreen.buildSummaryTooltipTexts(
			"1:100/2/900:903/",
			List.of(),
			6,
			10,
			String::length
		);

		assertEquals(List.of("1:100", "2", "900..."), tooltipLines);
	}

	/**
	 * 空摘要也应提供稳定占位，避免悬停时出现空 tooltip。
	 */
	@Test
	void buildSummaryTooltipTextsShouldReturnDashForEmptySummary() {
		List<String> tooltipLines = RepeaterEditorScreen.buildSummaryTooltipTexts("", List.of(), 8, 10, String::length);

		assertEquals(List.of("-"), tooltipLines);
	}
}
