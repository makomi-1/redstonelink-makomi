package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkFilterLifecycleState 状态机测试。
 */
@Tag("stable-core")
class LinkFilterLifecycleStateTest {
	/**
	 * 普通区块/上下文脱附不应删除已放置过滤器真值。
	 */
	@Test
	void normalContextDetachShouldKeepPersistedFilter() {
		LinkFilterLifecycleState state = new LinkFilterLifecycleState();

		state.onContextAttached();

		assertFalse(state.onContextDetachedShouldRemovePersistedFilter());
		assertFalse(state.contextAttached());
		assertFalse(state.physicalRemovalInProgress());
	}

	/**
	 * 物理移除后的上下文脱附应删除已放置过滤器真值。
	 */
	@Test
	void physicalRemovalShouldRemovePersistedFilter() {
		LinkFilterLifecycleState state = new LinkFilterLifecycleState();

		state.onContextAttached();
		state.onPhysicalRemovalStarted();

		assertTrue(state.physicalRemovalInProgress());
		assertTrue(state.onContextDetachedShouldRemovePersistedFilter());
		assertFalse(state.contextAttached());
		assertFalse(state.physicalRemovalInProgress());
	}

	/**
	 * 重新附着应清空旧的物理移除标记，避免把后续普通脱附误判成真实移除。
	 */
	@Test
	void reattachShouldClearOldPhysicalRemovalFlag() {
		LinkFilterLifecycleState state = new LinkFilterLifecycleState();

		state.onContextAttached();
		state.onPhysicalRemovalStarted();
		state.onContextAttached();

		assertTrue(state.contextAttached());
		assertFalse(state.physicalRemovalInProgress());
		assertFalse(state.onContextDetachedShouldRemovePersistedFilter());
	}
}
