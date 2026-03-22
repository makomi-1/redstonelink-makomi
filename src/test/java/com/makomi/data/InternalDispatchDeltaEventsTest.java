package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * InternalDispatchDeltaEvents 同步发布与防重行为测试。
 */
@Tag("stable-core")
class InternalDispatchDeltaEventsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

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

	/**
	 * 链路解绑应发布“triggerSource 其它失效”事件。
	 */
	@Test
	void publishLinkDetachedShouldEmitTriggerSourceInvalidation() throws Exception {
		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		withCrossChunkConfig(new Properties(), () ->
			InternalDispatchDeltaEvents.publishLinkDetached(
				dummyServerLevel(),
				LinkNodeType.TRIGGER_SOURCE,
				11L,
				Set.of(101L),
				EventMeta.of(400L, 0, 7L)
			)
		);

		assertEquals(ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION, published.get().deltaKind());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, published.get().sourceType());
		assertEquals(11L, published.get().sourceSerial());
		assertEquals(LinkNodeType.CORE, published.get().targetType());
		assertEquals(101L, published.get().targetSerial());
	}

	/**
	 * triggerSource 区块卸载失效默认关闭，未开启时不应发布事件。
	 */
	@Test
	void publishLinkChunkUnloadedShouldStaySilentWhenConfigDisabled() throws Exception {
		AtomicInteger counter = new AtomicInteger();
		InternalDispatchDeltaEvents.register(event -> counter.incrementAndGet());

		withCrossChunkConfig(new Properties(), () ->
			InternalDispatchDeltaEvents.publishLinkChunkUnloaded(
				dummyServerLevel(),
				LinkNodeType.TRIGGER_SOURCE,
				12L,
				Set.of(102L),
				EventMeta.of(500L, 0, 8L)
			)
		);

		assertEquals(0, counter.get());
	}

	/**
	 * triggerSource 区块卸载失效开启后，应发布仅清 sync 的 soft invalidation。
	 */
	@Test
	void publishLinkChunkUnloadedShouldEmitChunkUnloadInvalidationWhenEnabled() throws Exception {
		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceChunkUnloadInvalidation.enabled", "true");
		withCrossChunkConfig(properties, () ->
			InternalDispatchDeltaEvents.publishLinkChunkUnloaded(
				dummyServerLevel(),
				LinkNodeType.TRIGGER_SOURCE,
				13L,
				Set.of(103L),
				EventMeta.of(600L, 0, 9L)
			)
		);

		assertEquals(
			ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
			published.get().deltaKind()
		);
		assertEquals(13L, published.get().sourceSerial());
		assertEquals(103L, published.get().targetSerial());
	}

	/**
	 * 目标区块加载 replay 应沿用来源端快照时间键，而不是目标加载时刻。
	 */
	@Test
	void publishResolvedTargetChunkLoadSyncReplayShouldUseSnapshotEventMeta() {
		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot = new SyncReplaySourceBlockEntity.ReplaySyncSnapshot(
			9,
			EventMeta.of(701L, 0, 33L)
		);
		InternalDispatchDeltaEvents.publishResolvedTargetChunkLoadSyncReplay(dummyServerLevel(), 14L, 104L, replaySnapshot);

		assertEquals(ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL, published.get().deltaKind());
		assertEquals(14L, published.get().sourceSerial());
		assertEquals(104L, published.get().targetSerial());
		assertEquals(replaySnapshot.eventMeta(), published.get().eventMeta());
		assertEquals(9, published.get().syncSignalStrength());
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

	private static void withCrossChunkConfig(Properties properties, ThrowingRunnable action) throws Exception {
		Field field = RedstoneLinkConfig.class.getDeclaredField("crossChunkValues");
		field.setAccessible(true);
		Object previous = field.get(null);
		Object snapshot = parseCrossChunkValues(properties);
		try {
			field.set(null, snapshot);
			action.run();
		} finally {
			field.set(null, previous);
		}
	}

	private static Object parseCrossChunkValues(Properties properties) throws Exception {
		Method parseMethod = RedstoneLinkConfig.class.getDeclaredMethod("parseCrossChunk", Properties.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, properties);
	}

	private static ServerLevel dummyServerLevel() {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			Unsafe unsafe = (Unsafe) field.get(null);
			return (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to allocate dummy ServerLevel", ex);
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
