package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkDispatchFilterService 启动补采样与 replay 过滤契约测试。
 */
@Tag("stable-core")
class LinkDispatchFilterServiceTest {
	@AfterEach
	void resetServiceStateAfterEach() {
		LinkDispatchFilterService.resetForTesting();
	}

	/**
	 * register 入口应可重复调用，避免重复初始化时抛异常。
	 */
	@Test
	void registerShouldBeCallableRepeatedly() {
		LinkDispatchFilterService.register();
		LinkDispatchFilterService.register();
	}

	/**
	 * 启动补采样只应命中以邻居输入作为阈值来源的过滤器。
	 */
	@Test
	void shouldRefreshNeighborSignalAfterServerStartedShouldOnlyAcceptNeighborThresholdEntries() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		BlockPos fixedPos = new BlockPos(0, 64, 0);
		BlockPos neighborPos = new BlockPos(16, 64, 16);

		assertTrue(
			data.upsert(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				fixedPos,
				new LinkFilterConfigSnapshot(
					"",
					LinkFilterNodeSetMode.DISABLED,
					LinkFilterSignalThresholdSource.FIXED_INPUT,
					12,
					LinkFilterSignalMode.UPPER_BOUND
				),
				7
			)
		);
		assertTrue(
			data.upsert(
				LinkFilterKind.RECEIVE,
				Level.OVERWORLD,
				neighborPos,
				new LinkFilterConfigSnapshot(
					"",
					LinkFilterNodeSetMode.DISABLED,
					LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
					12,
					LinkFilterSignalMode.UPPER_BOUND
				),
				7
			)
		);

		assertFalse(LinkDispatchFilterService.shouldRefreshNeighborSignalAfterServerStarted(null));
		assertFalse(
			LinkDispatchFilterService.shouldRefreshNeighborSignalAfterServerStarted(
				data.entriesSnapshot().stream().filter(entry -> entry.filterPos().equals(fixedPos)).findFirst().orElseThrow()
			)
		);
		assertTrue(
			LinkDispatchFilterService.shouldRefreshNeighborSignalAfterServerStarted(
				data.entriesSnapshot().stream().filter(entry -> entry.filterPos().equals(neighborPos)).findFirst().orElseThrow()
			)
		);
	}

	/**
	 * 没有命中过滤器时，replay 应直接放行。
	 */
	@Test
	void allowsReplayByPersistedFiltersShouldAllowWhenNoFilterMatches() {
		assertTrue(
			LinkDispatchFilterService.allowsReplayByPersistedFilters(
				new PlacedLinkFilterSavedData(),
				Level.OVERWORLD,
				new BlockPos(0, 64, 0),
				11L,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				21L,
				9
			)
		);
	}

	/**
	 * send 过滤器命中且拦截来源时，replay 必须拒绝该来源。
	 */
	@Test
	void allowsReplayByPersistedFiltersShouldRejectWhenSendFilterBlocksTriggerSource() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		assertTrue(
			data.upsert(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				new BlockPos(0, 64, 0),
				nodeSetOnlyConfig("11", LinkFilterNodeSetMode.BLOCKLIST),
				0
			)
		);

		assertFalse(
			LinkDispatchFilterService.allowsReplayByPersistedFilters(
				data,
				Level.OVERWORLD,
				new BlockPos(1, 64, 1),
				11L,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				21L,
				9
			)
		);
	}

	/**
	 * receive 过滤器命中且拦截目标时，replay 必须拒绝该目标。
	 */
	@Test
	void allowsReplayByPersistedFiltersShouldRejectWhenReceiveFilterBlocksCore() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		assertTrue(
			data.upsert(
				LinkFilterKind.RECEIVE,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				nodeSetOnlyConfig("21", LinkFilterNodeSetMode.BLOCKLIST),
				0
			)
		);

		assertFalse(
			LinkDispatchFilterService.allowsReplayByPersistedFilters(
				data,
				Level.OVERWORLD,
				new BlockPos(0, 64, 0),
				11L,
				Level.OVERWORLD,
				new BlockPos(31, 64, 31),
				21L,
				9
			)
		);
	}

	/**
	 * send 与 receive 过滤器均明确放行时，replay 应允许发布。
	 */
	@Test
	void allowsReplayByPersistedFiltersShouldAllowWhenSendAndReceiveFiltersPass() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		assertTrue(
			data.upsert(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				new BlockPos(0, 64, 0),
				nodeSetOnlyConfig("11", LinkFilterNodeSetMode.WHITELIST),
				0
			)
		);
		assertTrue(
			data.upsert(
				LinkFilterKind.RECEIVE,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				nodeSetOnlyConfig("21", LinkFilterNodeSetMode.WHITELIST),
				0
			)
		);

		assertTrue(
			LinkDispatchFilterService.allowsReplayByPersistedFilters(
				data,
				Level.OVERWORLD,
				new BlockPos(2, 64, 2),
				11L,
				Level.OVERWORLD,
				new BlockPos(30, 64, 30),
				21L,
				9
			)
		);
	}

	private static LinkFilterConfigSnapshot nodeSetOnlyConfig(String serialExpression, LinkFilterNodeSetMode nodeSetMode) {
		return new LinkFilterConfigSnapshot(
			serialExpression,
			nodeSetMode,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);
	}
}
