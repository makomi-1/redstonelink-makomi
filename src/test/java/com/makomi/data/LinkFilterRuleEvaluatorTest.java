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
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.WHITELIST,
				Set.of(3L, 5L),
				0L,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			),
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.WHITELIST,
				Set.of(7L),
				0L,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			),
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.BLOCKLIST,
				Set.of(5L),
				0L,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 3L, 9));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 7L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 5L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 11L, 9));
	}

	/**
	 * 邻居最大输入阈值与下界模式应按当前采样值参与比较。
	 */
	@Test
	void allowsShouldRespectNeighborThresholdSourceAndLowerBoundMode() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.DISABLED,
				Set.of(),
				0L,
				LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
				2,
				LinkFilterSignalMode.LOWER_BOUND,
				9
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 1L, 9));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 1L, 12));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 1L, 8));
	}

	/**
	 * 邻居输入为 0 时，过滤器应作为总开关关闭，不再参与节点集与信号规则。
	 */
	@Test
	void allowsShouldSkipNodeSetAndSignalRulesWhenNeighborSignalIsZero() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.BLOCKLIST,
				Set.of(11L),
				0L,
				LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
				15,
				LinkFilterSignalMode.UPPER_BOUND,
				0
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 11L, 15));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 99L, 1));
	}

	/**
	 * 邻居输入恢复为非零后，过滤器原有规则应重新生效。
	 */
	@Test
	void allowsShouldReactivateRulesWhenNeighborSignalRestoresToNonZero() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.BLOCKLIST,
				Set.of(11L),
				0L,
				LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
				15,
				LinkFilterSignalMode.UPPER_BOUND,
				9
			)
		);

		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 11L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 12L, 10));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.SERIAL, 12L, 9));
	}

	/**
	 * 节点当前为频道模式时，应忽略序号过滤器，只对频道过滤器求值。
	 */
	@Test
	void allowsShouldOnlyUseFiltersMatchingCurrentTargetMode() {
		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = List.of(
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.SERIAL,
				LinkFilterNodeSetMode.BLOCKLIST,
				Set.of(11L),
				0L,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			),
			new LinkFilterRuleEvaluator.FilterRuntimeView(
				LinkFilterTargetMode.CHANNEL,
				LinkFilterNodeSetMode.WHITELIST,
				Set.of(),
				88L,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				15,
				LinkFilterSignalMode.DISABLED,
				8
			)
		);

		assertTrue(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.CHANNEL, 88L, 9));
		assertFalse(LinkFilterRuleEvaluator.allows(filters, LinkFilterTargetMode.CHANNEL, 77L, 9));
	}
}
