package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * InternalDispatchDeltaProjector 路由判定测试。
 */
@Tag("stable-core")
class InternalDispatchDeltaProjectorTest {
	/**
	 * 缺失目标节点时应跳过投影。
	 */
	@Test
	void resolveProjectionRouteShouldSkipWhenTargetMissing() {
		assertEquals(
			InternalDispatchDeltaProjector.ProjectionRoute.SKIP,
			InternalDispatchDeltaProjector.resolveProjectionRoute(false, false)
		);
		assertEquals(
			InternalDispatchDeltaProjector.ProjectionRoute.SKIP,
			InternalDispatchDeltaProjector.resolveProjectionRoute(false, true)
		);
	}

	/**
	 * 目标已加载时应走直接投影路径。
	 */
	@Test
	void resolveProjectionRouteShouldUseDirectForLoadedTarget() {
		assertEquals(
			InternalDispatchDeltaProjector.ProjectionRoute.DIRECT,
			InternalDispatchDeltaProjector.resolveProjectionRoute(true, true)
		);
	}

	/**
	 * 目标存在但未加载时应走跨区块队列路径。
	 */
	@Test
	void resolveProjectionRouteShouldQueueForUnloadedTarget() {
		assertEquals(
			InternalDispatchDeltaProjector.ProjectionRoute.QUEUE,
			InternalDispatchDeltaProjector.resolveProjectionRoute(true, false)
		);
	}

	/**
	 * loaded `SYNC` 在 queued_only 下仅异步链路进入 batch。
	 */
	@Test
	void shouldBatchLoadedDeltaShouldRespectQueuedOnlyForSync() {
		assertFalse(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.QUEUED_ONLY
			)
		);
		assertTrue(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.QUEUED_ONLY
			)
		);
	}

	/**
	 * loaded `SYNC` 在 all_sync/off 下应分别全开/全关。
	 */
	@Test
	void shouldBatchLoadedDeltaShouldApplyAllSyncAndOffModes() {
		assertTrue(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.ALL_SYNC
			)
		);
		assertFalse(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.OFF
			)
		);
	}

	/**
	 * 非 SYNC 的 loaded invalidation 仍只受 deliveryMode 控制，不受 direct sync 配置影响。
	 */
	@Test
	void shouldBatchLoadedDeltaShouldKeepNonSyncBehaviorStable() {
		assertFalse(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
				InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.ALL_SYNC
			)
		);
		assertTrue(
			InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
				ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.OFF
			)
		);
	}
}
