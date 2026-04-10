package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkDispatchFilterService 启动补采样契约测试。
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
}
