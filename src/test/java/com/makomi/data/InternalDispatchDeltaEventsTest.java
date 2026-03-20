package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * InternalDispatchDeltaEvents 同步发布与防重行为测试。
 */
@Tag("stable-core")
class InternalDispatchDeltaEventsTest {
	@AfterEach
	void tearDown() {
		InternalDispatchDeltaEvents.resetForTesting();
	}

	/**
	 * 同步发布应在当前调用栈内按监听器顺序执行。
	 */
	@Test
	void publishShouldInvokeListenerSynchronously() {
		AtomicInteger counter = new AtomicInteger();
		InternalDispatchDeltaEvents.Listener first = event -> counter.addAndGet(1);
		InternalDispatchDeltaEvents.Listener second = event -> counter.addAndGet(10);
		InternalDispatchDeltaEvents.register(first);
		InternalDispatchDeltaEvents.register(second);

		InternalDispatchDeltaEvents.publish(sampleEvent(100L, 0, 1L, 7));
		assertEquals(11, counter.get());
	}

	/**
	 * 同 tick 同键事件应被防重缓存抑制，避免重复投影。
	 */
	@Test
	void publishShouldDeduplicateSameEventInSameTick() {
		AtomicInteger counter = new AtomicInteger();
		InternalDispatchDeltaEvents.register(event -> counter.incrementAndGet());

		InternalDispatchDeltaEvents.DispatchDeltaEvent event = sampleEvent(200L, 0, 0L, 11);
		InternalDispatchDeltaEvents.publish(event);
		InternalDispatchDeltaEvents.publish(event);

		assertEquals(1, counter.get());
	}

	/**
	 * 不同时间键事件应分别下发，不被误判为重复。
	 */
	@Test
	void publishShouldKeepDistinctEvents() {
		AtomicInteger counter = new AtomicInteger();
		InternalDispatchDeltaEvents.register(event -> counter.incrementAndGet());

		InternalDispatchDeltaEvents.publish(sampleEvent(300L, 0, 0L, 5));
		InternalDispatchDeltaEvents.publish(sampleEvent(301L, 0, 0L, 5));

		assertEquals(2, counter.get());
	}

	/**
	 * 归一化在 triggerSource 视角下应保持 source->core 方向不变。
	 */
	@Test
	void normalizePairsShouldKeepDirectionForTriggerSourceView() {
		Set<InternalDispatchDeltaEvents.SourceTargetPair> pairs = InternalDispatchDeltaEvents.normalizeLinkPairsForTesting(
			LinkNodeType.TRIGGER_SOURCE,
			11L,
			Set.of(101L, 102L)
		);
		assertEquals(
			Set.of(
				new InternalDispatchDeltaEvents.SourceTargetPair(11L, 101L),
				new InternalDispatchDeltaEvents.SourceTargetPair(11L, 102L)
			),
			pairs
		);
	}

	/**
	 * 归一化在 core 视角下应交换为 triggerSource->core 固定方向。
	 */
	@Test
	void normalizePairsShouldSwapDirectionForCoreView() {
		Set<InternalDispatchDeltaEvents.SourceTargetPair> pairs = InternalDispatchDeltaEvents.normalizeLinkPairsForTesting(
			LinkNodeType.CORE,
			22L,
			Set.of(201L, 202L)
		);
		assertEquals(
			Set.of(
				new InternalDispatchDeltaEvents.SourceTargetPair(201L, 22L),
				new InternalDispatchDeltaEvents.SourceTargetPair(202L, 22L)
			),
			pairs
		);
	}

	/**
	 * 非法输入应返回空集合，避免产生无效来源对。
	 */
	@Test
	void normalizePairsShouldReturnEmptyForInvalidInput() {
		Set<InternalDispatchDeltaEvents.SourceTargetPair> pairs = InternalDispatchDeltaEvents.normalizeLinkPairsForTesting(
			LinkNodeType.CORE,
			0L,
			Set.of(1L)
		);
		assertTrue(pairs.isEmpty());
	}

	private static InternalDispatchDeltaEvents.DispatchDeltaEvent sampleEvent(
		long tick,
		int slot,
		long seq,
		int syncStrength
	) {
		return new InternalDispatchDeltaEvents.DispatchDeltaEvent(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			2L,
			ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
			ActivatableTargetBlockEntity.DeltaAction.UPSERT,
			ActivationMode.TOGGLE,
			syncStrength,
			EventMeta.of(tick, slot, seq)
		);
	}
}
