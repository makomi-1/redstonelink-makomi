package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkedTargetDispatchService 语义分发契约测试。
 */
@Tag("stable-core")
class LinkedTargetDispatchServiceTest {
	/**
	 * activationMode 为空时应走空摘要兜底。
	 */
	@Test
	void dispatchActivationShouldReturnEmptySummaryWhenModeIsNull() {
		LinkedTargetDispatchService.DispatchSummary summary = LinkedTargetDispatchService.dispatchActivation(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(10L, 20L),
			null
		);

		assertEquals(2, summary.totalTargets());
		assertEquals(0, summary.handledCount());
		assertFalse(summary.hasCrossChunkHandled());
	}

	/**
	 * 入参非法时应返回空摘要。
	 */
	@Test
	void dispatchShouldRejectInvalidArguments() {
		LinkedTargetDispatchService.DispatchSummary nullLevel = LinkedTargetDispatchService.dispatchActivation(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(100L),
			ActivationMode.TOGGLE
		);
		assertEquals(0, nullLevel.handledCount());

		LinkedTargetDispatchService.DispatchSummary emptyTargets = LinkedTargetDispatchService.dispatchSyncSignal(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			Set.of(),
			15
		);
		assertEquals(0, emptyTargets.totalTargets());
	}

	/**
	 * 跨区块提示构建在空输入与无接管时应返回空列表。
	 */
	@Test
	void buildCrossChunkNotifyMessagesShouldReturnEmptyWhenNoCrossChunkHandled() {
		assertTrue(LinkedTargetDispatchService.buildCrossChunkNotifyMessages(null).isEmpty());

		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			2,
			0,
			List.of(),
			List.of(),
			Set.of()
		);
		assertTrue(LinkedTargetDispatchService.buildCrossChunkNotifyMessages(summary).isEmpty());
	}

	/**
	 * 跨区块提示应同时覆盖强加载与“目标区块已卸载”的 relay 场景，并按总接管数统计 header。
	 */
	@Test
	void buildCrossChunkNotifyMessagesShouldIncludeRelayTargetsAndTotalHandledCount() {
		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			3,
			3,
			List.of(11L),
			List.of(21L, 22L),
			Set.of()
		);

		List<Component> lines = LinkedTargetDispatchService.buildCrossChunkNotifyMessages(summary);
		assertEquals(4, lines.size());

		TranslatableContents header = requireTranslatable(lines.get(0));
		assertEquals("message.redstonelink.crosschunk.notify.header", header.getKey());
		assertEquals(3, header.getArgs()[0]);

		TranslatableContents source = requireTranslatable(lines.get(1));
		assertEquals("message.redstonelink.crosschunk.notify.source", source.getKey());
		assertEquals("triggerSource", source.getArgs()[0]);
		assertEquals(1L, source.getArgs()[1]);

		TranslatableContents forceLoadTargets = requireTranslatable(lines.get(2));
		assertEquals("message.redstonelink.crosschunk.notify.force_load_targets", forceLoadTargets.getKey());
		assertEquals("core:11", forceLoadTargets.getArgs()[0]);

		TranslatableContents relayTargets = requireTranslatable(lines.get(3));
		assertEquals("message.redstonelink.crosschunk.notify.relay_targets", relayTargets.getKey());
		assertEquals("core:21, core:22", relayTargets.getArgs()[0]);
	}

	/**
	 * 目标文本格式化应按 displayLimit 截断并附加剩余数量。
	 */
	@Test
	void formatNotifyTargetsShouldApplyDisplayLimit() throws Exception {
		Method formatMethod = LinkedTargetDispatchService.class.getDeclaredMethod(
			"formatNotifyTargets",
			LinkNodeType.class,
			List.class,
			int.class
		);
		formatMethod.setAccessible(true);

		assertEquals("-", formatMethod.invoke(null, LinkNodeType.CORE, List.of(), 3));
		assertEquals(
			"core:4, core:5 (+1)",
			formatMethod.invoke(null, LinkNodeType.CORE, List.of(4L, 5L, 6L), 2)
		);
		assertEquals(
			"core:4 (+2)",
			formatMethod.invoke(null, LinkNodeType.CORE, List.of(4L, 5L, 6L), 0)
		);
	}

	/**
	 * 序号快照应按升序返回不可变列表。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void immutableSortedSerialsShouldReturnSortedImmutableList() throws Exception {
		Method sortMethod = LinkedTargetDispatchService.class.getDeclaredMethod("immutableSortedSerials", List.class);
		sortMethod.setAccessible(true);

		List<Long> sorted = (List<Long>) sortMethod.invoke(null, List.of(9L, 1L, 5L));
		assertEquals(List.of(1L, 5L, 9L), sorted);
		assertThrows(UnsupportedOperationException.class, () -> sorted.add(10L));
	}

	/**
	 * DispatchSummary 统计方法应正确反映跨区块接管数。
	 */
	@Test
	void dispatchSummaryShouldReportCrossChunkHandledCount() {
		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			3,
			1,
			List.of(11L, 12L),
			List.of(21L),
			Set.of()
		);
		assertEquals(3, summary.crossChunkHandledCount());
		assertTrue(summary.hasCrossChunkHandled());
	}

	/**
	 * DispatchSummary 应能报告“本次成功处理目标所在维度”。
	 */
	@Test
	void dispatchSummaryShouldReportHandledTargetDimensions() {
		LinkedTargetDispatchService.DispatchSummary summary = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			2,
			1,
			List.of(),
			List.of(),
			Set.of(net.minecraft.world.level.Level.END)
		);

		assertTrue(summary.hasHandledTargetInDimension(net.minecraft.world.level.Level.END));
		assertFalse(summary.hasHandledTargetInDimension(net.minecraft.world.level.Level.NETHER));
	}

	/**
	 * direct loaded 派发仅在 `all_direct` 下开放 activation batching；`queued_only` 仍只放行异步 sync。
	 */
	@Test
	void shouldBatchLoadedDispatchShouldOnlyEnableAllDirectForLoadedDirectDispatch() throws Exception {
		Class<?> dispatchKindClass = Class.forName("com.makomi.data.LinkedTargetDispatchService$DispatchKind");
		Method method = LinkedTargetDispatchService.class.getDeclaredMethod(
			"shouldBatchLoadedDispatch",
			dispatchKindClass,
			RedstoneLinkConfig.CrossChunkDirectBatchingMode.class
		);
		method.setAccessible(true);

		Object syncKind = java.util.Arrays
			.stream(dispatchKindClass.getEnumConstants())
			.filter(constant -> ((Enum<?>) constant).name().equals("SYNC_SIGNAL"))
			.findFirst()
			.orElseThrow();
		Object activationKind = java.util.Arrays
			.stream(dispatchKindClass.getEnumConstants())
			.filter(constant -> ((Enum<?>) constant).name().equals("ACTIVATION"))
			.findFirst()
			.orElseThrow();

		assertFalse(
			(boolean) method.invoke(
				null,
				syncKind,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.QUEUED_ONLY
			)
		);
		assertTrue(
			(boolean) method.invoke(
				null,
				syncKind,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT
			)
		);
		assertFalse(
			(boolean) method.invoke(
				null,
				activationKind,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.QUEUED_ONLY
			)
		);
		assertFalse(
			(boolean) method.invoke(
				null,
				activationKind,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.OFF
			)
		);
		assertTrue(
			(boolean) method.invoke(
				null,
				activationKind,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT
			)
		);
	}

	/**
	 * 频道模式 loaded direct 只有在 `directBatching!=off` 时才应进入频道中间层。
	 */
	@Test
	void shouldStageLoadedTargetsIntoChannelBucketShouldSkipChannelSchedulerWhenDirectBatchingOff() {
		assertFalse(
			LinkedTargetDispatchService.shouldStageLoadedTargetsIntoChannelBucket(
				LinkNodeType.TRIGGER_SOURCE,
				LinkNodeType.CORE,
				LinkConnectionMode.CHANNEL,
				7L,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.OFF
			)
		);
		assertTrue(
			LinkedTargetDispatchService.shouldStageLoadedTargetsIntoChannelBucket(
				LinkNodeType.TRIGGER_SOURCE,
				LinkNodeType.CORE,
				LinkConnectionMode.CHANNEL,
				7L,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT
			)
		);
		assertFalse(
			LinkedTargetDispatchService.shouldStageLoadedTargetsIntoChannelBucket(
				LinkNodeType.TRIGGER_SOURCE,
				LinkNodeType.CORE,
				LinkConnectionMode.CHANNEL,
				0L,
				RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT
			)
		);
	}

	private static TranslatableContents requireTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents);
		return (TranslatableContents) component.getContents();
	}
}
