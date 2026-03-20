package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
