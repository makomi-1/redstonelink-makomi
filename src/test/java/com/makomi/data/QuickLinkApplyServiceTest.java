package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * QuickLinkApplyService 数量限制契约测试。
 */
@Tag("stable-core")
class QuickLinkApplyServiceTest {
	/**
	 * quick-link 应用到 core 的 triggerSource 数量上限应复用 `server.maxTargetsPerSetLinks`。
	 */
	@Test
	void parseCachedTriggerSourcesShouldReuseGeneralMaxTargetsPerSetLinks() {
		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		String withinLimit = buildSerialExpression(maxTargets);
		String exceedLimit = buildSerialExpression(maxTargets + 1);

		SerialParseUtil.OrderedTargetParseResult withinResult = QuickLinkApplyService.parseCachedTriggerSources(withinLimit);
		SerialParseUtil.OrderedTargetParseResult exceedResult = QuickLinkApplyService.parseCachedTriggerSources(exceedLimit);

		assertEquals(maxTargets, QuickLinkApplyService.maxQuickLinkApplyTargetCount());
		assertFalse(withinResult.exceedLimit());
		assertTrue(exceedResult.exceedLimit());
	}

	/**
	 * quick-link 批量应用到 core 时，写控设置量应等于当前缓存数量。
	 */
	@Test
	void writeControlSetSizeShouldMatchCachedTriggerSourceCount() {
		assertEquals(0, QuickLinkApplyService.writeControlSetSizeForCachedTriggerSourcesToCore(-3));
		assertEquals(0, QuickLinkApplyService.writeControlSetSizeForCachedTriggerSourcesToCore(0));
		assertEquals(7, QuickLinkApplyService.writeControlSetSizeForCachedTriggerSourcesToCore(7));
	}

	/**
	 * quick-link 写控失败应统一映射为笼统权限不足提示。
	 */
	@Test
	void failureFromWriteDecisionShouldAlwaysUsePermissionInsufficient() {
		assertEquals(
			"message.redstonelink.permission.insufficient",
			QuickLinkApplyService.failureFromWriteDecision(LinkWriteControlService.WriteDecision.denyReadonly(2)).messageKey()
		);
		assertEquals(
			"message.redstonelink.permission.insufficient",
			QuickLinkApplyService.failureFromWriteDecision(LinkWriteControlService.WriteDecision.denyLimited(8, 4, 2)).messageKey()
		);
		assertEquals(
			"message.redstonelink.permission.insufficient",
			QuickLinkApplyService.failureFromWriteDecision(
				LinkWriteControlService.WriteDecision.denyProtected(LinkNodeType.CORE, 12L, 2)
			).messageKey()
		);
	}

	/**
	 * 构造 `1/2/3/...` 形式的序号表达式。
	 */
	private static String buildSerialExpression(int count) {
		return LongStream.rangeClosed(1, count).mapToObj(Long::toString).collect(Collectors.joining("/"));
	}
}
