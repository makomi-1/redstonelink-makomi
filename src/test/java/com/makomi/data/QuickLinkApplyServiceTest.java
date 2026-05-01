package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import java.util.Set;
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
	 * 过滤器应用必须要求缓存类型与过滤器服务节点类型一致。
	 */
	@Test
	void filterCompatibilityShouldFollowFilterServicedNodeType() {
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithFilter(LinkNodeType.TRIGGER_SOURCE, LinkFilterKind.SEND));
		assertFalse(QuickLinkApplyService.isCacheTypeCompatibleWithFilter(LinkNodeType.CORE, LinkFilterKind.SEND));
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithFilter(LinkNodeType.CORE, LinkFilterKind.RECEIVE));
		assertFalse(QuickLinkApplyService.isCacheTypeCompatibleWithFilter(LinkNodeType.TRIGGER_SOURCE, LinkFilterKind.RECEIVE));
	}

	/**
	 * triggerSource 命中时，三态应用应只修改当前 triggerSource 的一跳目标集合。
	 */
	@Test
	void buildNextSourceTargetsShouldRespectReplaceAppendAndRemove() {
		assertEquals(
			Set.of(4L),
			QuickLinkApplyService.buildNextSourceTargets(
				Set.of(1L, 2L),
				List.of(4L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals(
			Set.of(1L, 2L, 4L),
			QuickLinkApplyService.buildNextSourceTargets(
				Set.of(1L, 2L),
				List.of(4L, 2L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals(
			Set.of(2L, 4L),
			QuickLinkApplyService.buildNextSourceTargets(
				Set.of(1L, 2L, 4L),
				List.of(1L, 7L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
	}

	/**
	 * `replace` + 空缓存应允许把命中 triggerSource 的一跳目标清空。
	 */
	@Test
	void buildNextSourceTargetsShouldAllowReplaceWithEmptyCache() {
		assertTrue(QuickLinkApplyService.allowsEmptySerialCacheApply(QuickLinkToolData.ApplyEditMode.REPLACE));
		assertFalse(QuickLinkApplyService.allowsEmptySerialCacheApply(QuickLinkToolData.ApplyEditMode.APPEND));
		assertFalse(QuickLinkApplyService.allowsEmptySerialCacheApply(QuickLinkToolData.ApplyEditMode.REMOVE));
		assertEquals(
			Set.of(),
			QuickLinkApplyService.buildNextSourceTargets(
				Set.of(1L, 2L),
				List.of(),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
	}

	/**
	 * core 命中时，三态应用应只修改当前 core 的一跳 triggerSource 集合。
	 */
	@Test
	void buildDesiredTriggerSourcesForCoreApplyShouldRespectReplaceAppendAndRemove() {
		assertEquals(
			List.of(4L),
			QuickLinkApplyService.buildDesiredTriggerSourcesForCoreApply(
				Set.of(1L, 2L),
				List.of(4L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals(
			List.of(1L, 2L, 4L),
			QuickLinkApplyService.buildDesiredTriggerSourcesForCoreApply(
				Set.of(2L, 1L),
				List.of(4L, 2L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals(
			List.of(2L, 4L),
			QuickLinkApplyService.buildDesiredTriggerSourcesForCoreApply(
				Set.of(1L, 2L, 4L),
				List.of(1L, 7L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
	}

	/**
	 * `replace` + 空缓存应允许把命中 core 的一跳来源清空。
	 */
	@Test
	void buildDesiredTriggerSourcesForCoreApplyShouldAllowReplaceWithEmptyCache() {
		assertEquals(
			List.of(),
			QuickLinkApplyService.buildDesiredTriggerSourcesForCoreApply(
				Set.of(1L, 2L),
				List.of(),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
	}

	/**
	 * 转发器输入侧应复用 `core` 目标语义。
	 */
	@Test
	void resolveRepeaterTargetTypeShouldMapTriggerSourceCacheToCore() {
		assertEquals(LinkNodeType.CORE, QuickLinkApplyService.resolveRepeaterTargetType(LinkNodeType.TRIGGER_SOURCE));
	}

	/**
	 * 转发器输出侧应复用 `triggerSource` 目标语义。
	 */
	@Test
	void resolveRepeaterTargetTypeShouldMapCoreCacheToTriggerSource() {
		assertEquals(LinkNodeType.TRIGGER_SOURCE, QuickLinkApplyService.resolveRepeaterTargetType(LinkNodeType.CORE));
	}

	/**
	 * 过滤器命中时，三态应用应在当前表达式上做覆盖或有序增删。
	 */
	@Test
	void buildNextFilterOrderedSerialsShouldRespectReplaceAppendAndRemove() {
		assertEquals(
			List.of(4L),
			QuickLinkApplyService.buildNextFilterOrderedSerials(
				List.of(1L, 2L),
				List.of(4L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals(
			List.of(1L, 2L, 4L),
			QuickLinkApplyService.buildNextFilterOrderedSerials(
				List.of(1L, 2L),
				List.of(4L, 2L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals(
			List.of(2L),
			QuickLinkApplyService.buildNextFilterOrderedSerials(
				List.of(1L, 2L, 4L),
				List.of(1L, 7L, 4L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
	}

	/**
	 * `replace` + 空缓存应允许清空过滤器节点集。
	 */
	@Test
	void buildNextFilterOrderedSerialsShouldAllowReplaceWithEmptyCache() {
		assertEquals(
			List.of(),
			QuickLinkApplyService.buildNextFilterOrderedSerials(
				List.of(1L, 2L, 4L),
				List.of(),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
	}

	/**
	 * 过滤器 quick-link 应用应只覆盖序号表达式，并保留原有节点集与信号配置。
	 */
	@Test
	void buildFilterSnapshotForAppliedCacheShouldOnlyReplaceSerialExpression() {
		LinkFilterConfigSnapshot currentSnapshot = new LinkFilterConfigSnapshot(
			"3/5",
			LinkFilterNodeSetMode.WHITELIST,
			LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
			9,
			LinkFilterSignalMode.LOWER_BOUND
		);

		LinkFilterConfigSnapshot nextSnapshot = QuickLinkApplyService.buildFilterSnapshotForAppliedCache(
			currentSnapshot,
			java.util.List.of(12L, 8L, 21L)
		);

		assertEquals("12/8/21", nextSnapshot.serialExpression());
		assertEquals(LinkFilterTargetMode.SERIAL, nextSnapshot.targetMode());
		assertEquals(0L, nextSnapshot.channel());
		assertEquals(currentSnapshot.nodeSetMode(), nextSnapshot.nodeSetMode());
		assertEquals(currentSnapshot.signalThresholdSource(), nextSnapshot.signalThresholdSource());
		assertEquals(currentSnapshot.fixedSignalThreshold(), nextSnapshot.fixedSignalThreshold());
		assertEquals(currentSnapshot.signalMode(), nextSnapshot.signalMode());
	}

	/**
	 * 过滤器应用空缓存时，应回写为空表达式。
	 */
	@Test
	void buildFilterSnapshotForAppliedCacheShouldAllowEmptyExpression() {
		LinkFilterConfigSnapshot nextSnapshot = QuickLinkApplyService.buildFilterSnapshotForAppliedCache(
			new LinkFilterConfigSnapshot("3/5", null, null, 15, null),
			List.of()
		);

		assertEquals("", nextSnapshot.serialExpression());
		assertEquals(LinkFilterTargetMode.SERIAL, nextSnapshot.targetMode());
	}

	/**
	 * 过滤器频道缓存应用应切到频道模式，并清空原有序号表达式。
	 */
	@Test
	void buildChannelFilterSnapshotForAppliedCacheShouldOnlyReplaceChannelValue() {
		LinkFilterConfigSnapshot currentSnapshot = new LinkFilterConfigSnapshot(
			"3/5",
			LinkFilterNodeSetMode.BLOCKLIST,
			LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
			9,
			LinkFilterSignalMode.LOWER_BOUND
		);

		LinkFilterConfigSnapshot nextSnapshot = QuickLinkApplyService.buildChannelFilterSnapshotForAppliedCache(currentSnapshot, 88L);

		assertEquals("", nextSnapshot.serialExpression());
		assertEquals(LinkFilterTargetMode.CHANNEL, nextSnapshot.targetMode());
		assertEquals(88L, nextSnapshot.channel());
		assertEquals(currentSnapshot.nodeSetMode(), nextSnapshot.nodeSetMode());
		assertEquals(currentSnapshot.signalThresholdSource(), nextSnapshot.signalThresholdSource());
		assertEquals(currentSnapshot.fixedSignalThreshold(), nextSnapshot.fixedSignalThreshold());
		assertEquals(currentSnapshot.signalMode(), nextSnapshot.signalMode());
	}

	/**
	 * 过滤器频道缓存应用应允许 `0` 透传，以便与 quick-link 的清空语义保持一致。
	 */
	@Test
	void buildChannelFilterSnapshotForAppliedCacheShouldAllowZeroChannel() {
		LinkFilterConfigSnapshot nextSnapshot = QuickLinkApplyService.buildChannelFilterSnapshotForAppliedCache(
			new LinkFilterConfigSnapshot("3/5", null, null, 15, null),
			0L
		);

		assertEquals(LinkFilterTargetMode.CHANNEL, nextSnapshot.targetMode());
		assertEquals(0L, nextSnapshot.channel());
		assertEquals("", nextSnapshot.serialExpression());
	}

	/**
	 * 区块激活器应用必须要求缓存类型与当前生效服务对象一致。
	 */
	@Test
	void chunkActivatorCompatibilityShouldFollowActiveType() {
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithChunkActivator(LinkNodeType.TRIGGER_SOURCE, LinkNodeType.TRIGGER_SOURCE));
		assertFalse(QuickLinkApplyService.isCacheTypeCompatibleWithChunkActivator(LinkNodeType.CORE, LinkNodeType.TRIGGER_SOURCE));
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithChunkActivator(LinkNodeType.CORE, LinkNodeType.CORE));
		assertFalse(QuickLinkApplyService.isCacheTypeCompatibleWithChunkActivator(LinkNodeType.TRIGGER_SOURCE, LinkNodeType.CORE));
	}

	/**
	 * 转发器 quick-link 应允许 `triggerSource/core` 两类缓存类型，分别写入输入/输出配置。
	 */
	@Test
	void repeaterCompatibilityShouldAllowTriggerSourceAndCoreCaches() {
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithRepeater(LinkNodeType.TRIGGER_SOURCE));
		assertTrue(QuickLinkApplyService.isCacheTypeCompatibleWithRepeater(LinkNodeType.CORE));
		assertFalse(QuickLinkApplyService.isCacheTypeCompatibleWithRepeater(null));
	}

	/**
	 * 转发器输入侧 quick-link 应只修改输入表达式，并保留输出表达式与延迟配置。
	 */
	@Test
	void buildRepeaterSnapshotForAppliedCacheShouldOnlyTouchInputExpressionForTriggerSourceCache() {
		RepeaterConfigSnapshot currentSnapshot = new RepeaterConfigSnapshot("1/2", "7/9", RepeaterDelay.TWO_TICKS);

		RepeaterConfigSnapshot replacedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.TRIGGER_SOURCE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.TRIGGER_SOURCE,
				List.of(9L, 11L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals("9/11", replacedSnapshot.inputSerialExpression());
		assertEquals("7/9", replacedSnapshot.outputSerialExpression());
		assertEquals(RepeaterDelay.TWO_TICKS, replacedSnapshot.delay());

		RepeaterConfigSnapshot appendedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.TRIGGER_SOURCE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.TRIGGER_SOURCE,
				List.of(11L, 2L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals("1/2/11", appendedSnapshot.inputSerialExpression());

		RepeaterConfigSnapshot removedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.TRIGGER_SOURCE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.TRIGGER_SOURCE,
				List.of(1L, 99L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
		assertEquals("2", removedSnapshot.inputSerialExpression());
	}

	/**
	 * 转发器输出侧 quick-link 应只修改输出表达式，并保留输入表达式与延迟配置。
	 */
	@Test
	void buildRepeaterSnapshotForAppliedCacheShouldOnlyTouchOutputExpressionForCoreCache() {
		RepeaterConfigSnapshot currentSnapshot = new RepeaterConfigSnapshot("1/2", "7/9", RepeaterDelay.ONE_TICK);

		RepeaterConfigSnapshot replacedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.CORE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.CORE,
				List.of(15L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals("1/2", replacedSnapshot.inputSerialExpression());
		assertEquals("15", replacedSnapshot.outputSerialExpression());
		assertEquals(RepeaterDelay.ONE_TICK, replacedSnapshot.delay());

		RepeaterConfigSnapshot appendedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.CORE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.CORE,
				List.of(15L, 7L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals("7/9/15", appendedSnapshot.outputSerialExpression());

		RepeaterConfigSnapshot removedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.CORE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.CORE,
				List.of(7L, 42L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
		assertEquals("9", removedSnapshot.outputSerialExpression());
	}

	/**
	 * 转发器 quick-link 应保留自定义正整数延迟配置。
	 */
	@Test
	void buildRepeaterSnapshotForAppliedCacheShouldPreserveCustomPositiveDelay() {
		RepeaterConfigSnapshot currentSnapshot = new RepeaterConfigSnapshot("1/2", "7/9", RepeaterDelay.ofTicks(5));

		RepeaterConfigSnapshot replacedSnapshot = QuickLinkApplyService.buildRepeaterSnapshotForAppliedCache(
			currentSnapshot,
			LinkNodeType.TRIGGER_SOURCE,
			QuickLinkApplyService.buildNextRepeaterOrderedSerials(
				currentSnapshot,
				LinkNodeType.TRIGGER_SOURCE,
				List.of(15L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);

		assertEquals("15", replacedSnapshot.inputSerialExpression());
		assertEquals("7/9", replacedSnapshot.outputSerialExpression());
		assertEquals(RepeaterDelay.ofTicks(5), replacedSnapshot.delay());
	}

	/**
	 * 区块激活器 quick-link 应只修改当前生效服务对象的节点集，并保留另一套配置与模式。
	 */
	@Test
	void buildChunkActivatorSnapshotForAppliedCacheShouldOnlyTouchActiveConfig() {
		ChunkActivatorConfigStateSnapshot currentSnapshot = new ChunkActivatorConfigStateSnapshot(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("1/2", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("7/9", ChunkActivatorMode.RESIDENT)
		);

		ChunkActivatorConfigStateSnapshot replacedSnapshot = QuickLinkApplyService.buildChunkActivatorSnapshotForAppliedCache(
			currentSnapshot,
			QuickLinkApplyService.buildNextChunkActivatorOrderedSerials(
				currentSnapshot,
				List.of(9L, 11L),
				QuickLinkToolData.ApplyEditMode.REPLACE
			)
		);
		assertEquals(LinkNodeType.CORE, replacedSnapshot.activeType());
		assertEquals("1/2", replacedSnapshot.triggerSourceConfig().serialExpression());
		assertEquals(ChunkActivatorMode.FORCE_LOAD, replacedSnapshot.triggerSourceConfig().mode());
		assertEquals("9/11", replacedSnapshot.coreConfig().serialExpression());
		assertEquals(ChunkActivatorMode.RESIDENT, replacedSnapshot.coreConfig().mode());

		ChunkActivatorConfigStateSnapshot appendedSnapshot = QuickLinkApplyService.buildChunkActivatorSnapshotForAppliedCache(
			currentSnapshot,
			QuickLinkApplyService.buildNextChunkActivatorOrderedSerials(
				currentSnapshot,
				List.of(11L, 7L),
				QuickLinkToolData.ApplyEditMode.APPEND
			)
		);
		assertEquals("7/9/11", appendedSnapshot.coreConfig().serialExpression());

		ChunkActivatorConfigStateSnapshot removedSnapshot = QuickLinkApplyService.buildChunkActivatorSnapshotForAppliedCache(
			currentSnapshot,
			QuickLinkApplyService.buildNextChunkActivatorOrderedSerials(
				currentSnapshot,
				List.of(7L, 15L),
				QuickLinkToolData.ApplyEditMode.REMOVE
			)
		);
		assertEquals("9", removedSnapshot.coreConfig().serialExpression());
	}

	/**
	 * 构造 `1/2/3/...` 形式的序号表达式。
	 */
	private static String buildSerialExpression(int count) {
		return LongStream.rangeClosed(1, count).mapToObj(Long::toString).collect(Collectors.joining("/"));
	}
}
