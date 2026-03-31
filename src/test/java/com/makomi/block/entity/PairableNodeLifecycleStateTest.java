package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PairableNodeLifecycleState 状态机测试。
 * <p>
 * 这些用例专门卡住生命周期重构的两个核心风险：
 * </p>
 * <ul>
 * <li>风险 1：物理移除不应被重复发布为普通 context-detach；</li>
 * <li>风险 2：serial 未就绪前不应提前发布 attach。</li>
 * </ul>
 */
@Tag("stable-core")
class PairableNodeLifecycleStateTest {
	/**
	 * 上下文附着后，只有在外部条件真正就绪时才允许发布 attach。
	 */
	@Test
	void attachShouldWaitUntilPublishReady() {
		PairableNodeLifecycleState state = new PairableNodeLifecycleState();

		state.onContextAttached();

		assertTrue(state.contextAttached());
		assertTrue(state.attachPending());
		assertFalse(state.attachPublished());
		assertFalse(state.tryMarkAttachPublished(false));
		assertTrue(state.attachPending());
		assertFalse(state.attachPublished());

		assertTrue(state.tryMarkAttachPublished(true));
		assertFalse(state.attachPending());
		assertTrue(state.attachPublished());
	}

	/**
	 * 物理移除中的后继 `setRemoved()` 不应再作为普通 context-detach 发布。
	 */
	@Test
	void physicalRemovalShouldSuppressDetachPublish() {
		PairableNodeLifecycleState state = new PairableNodeLifecycleState();

		state.onContextAttached();
		assertTrue(state.tryMarkAttachPublished(true));
		state.onPhysicalRemovalStarted();

		assertFalse(state.onContextDetached(true));
		assertFalse(state.contextAttached());
		assertFalse(state.attachPending());
		assertFalse(state.attachPublished());
		assertFalse(state.physicalRemovalInProgress());
	}

	/**
	 * 同一方块实体切换节点身份后，应要求重新发布 attach，而不是沿用旧身份状态。
	 */
	@Test
	void identityChangeWhileAttachedShouldRequireNewAttachPublish() {
		PairableNodeLifecycleState state = new PairableNodeLifecycleState();

		state.onContextAttached();
		assertTrue(state.tryMarkAttachPublished(true));

		state.onNodeIdentityChangedWhileAttached();

		assertTrue(state.contextAttached());
		assertTrue(state.attachPending());
		assertFalse(state.attachPublished());
		assertTrue(state.tryMarkAttachPublished(true));
		assertFalse(state.attachPending());
		assertTrue(state.attachPublished());
	}

	/**
	 * 若节点在发布条件就绪前即脱附，则不应发布普通 detach。
	 */
	@Test
	void detachBeforeAttachPublishShouldStaySilent() {
		PairableNodeLifecycleState state = new PairableNodeLifecycleState();

		state.onContextAttached();

		assertFalse(state.onContextDetached(false));
		assertFalse(state.contextAttached());
		assertFalse(state.attachPending());
		assertFalse(state.attachPublished());
	}
}
