package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 过滤器规则求值测试。
 */
@Tag("stable-core")
class LinkFilterRuleEvaluatorTest {
	/**
	 * 多个白名单过滤器应按并集放行，但命中过滤对象后仍应优先阻断。
	 */
	@Test
	void allowsShouldUseWhitelistUnionAndBlocklistPrecedence() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterNodeSetMode.WHITELIST,
				Set.of(3L, 5L),
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			),
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterNodeSetMode.WHITELIST,
				Set.of(7L),
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			),
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterNodeSetMode.BLOCKLIST,
				Set.of(5L),
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, 3L, 9));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, 7L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, 5L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, 11L, 9));
	}

	/**
	 * 邻居最大输入阈值与下界模式应按当前采样值参与比较。
	 */
	@Test
	void allowsShouldRespectNeighborThresholdSourceAndLowerBoundMode() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterNodeSetMode.DISABLED,
				Set.of(),
				LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
				2,
				LinkFilterSignalMode.LOWER_BOUND,
				9
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, 1L, 9));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, 1L, 12));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, 1L, 8));
	}
}
