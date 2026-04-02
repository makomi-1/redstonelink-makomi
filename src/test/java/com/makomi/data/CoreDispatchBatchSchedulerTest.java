package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CoreDispatchBatchScheduler 内部聚合规约测试。
 */
@Tag("stable-core")
class CoreDispatchBatchSchedulerTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 同一 `dueTick` bucket 内批量 merge 时，同源同 kind 应只保留时间键更新的一条。
	 */
	@Test
	void mergeAllShouldKeepLatestEntryPerSourceAndKindWithinSameDueTickBucket() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(
			invokeMergeAll(
				accumulator,
				120L,
				List.of(
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						11L,
						ActivationMode.TOGGLE,
						4,
						ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 1L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						11L,
						ActivationMode.TOGGLE,
						12,
						ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 2L)
					)
				)
			)
		);

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator, 120L);
		assertEquals(1, entries.size());
		ActivatableTargetBlockEntity.DispatchBatchEntry mergedEntry =
			(ActivatableTargetBlockEntity.DispatchBatchEntry) entries.values().iterator().next();
		assertEquals(12, mergedEntry.syncSignalStrength());
		assertEquals(2L, mergedEntry.eventMeta().seq());
	}

	/**
	 * 同一 `dueTick` bucket 内，完整 invalidation 应覆盖更早的 sync / source invalidation / chunk-unload invalidation。
	 */
	@Test
	void mergeAllShouldDropCoveredEntriesWhenFullInvalidationArrivesWithinSameDueTickBucket() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(
			invokeMergeAll(
				accumulator,
				130L,
				List.of(
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.TOGGLE,
						15,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 1L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION,
						ActivatableTargetBlockEntity.DeltaAction.REMOVE,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.TOGGLE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 2L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
						ActivatableTargetBlockEntity.DeltaAction.REMOVE,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.TOGGLE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 3L)
					)
				)
			)
		);

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator, 130L);
		assertEquals(1, entries.size());
		ActivatableTargetBlockEntity.DispatchBatchEntry mergedEntry =
			(ActivatableTargetBlockEntity.DispatchBatchEntry) entries.values().iterator().next();
		assertEquals(ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION, mergedEntry.deltaKind());
		assertEquals(3L, mergedEntry.eventMeta().seq());
	}

	/**
	 * scheduler 的 accumulator 对象池应限制 retained 数量，避免历史峰值长期驻留。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void recycleAccumulatorShouldCapRetainedPoolSize() throws Exception {
		CoreDispatchBatchScheduler.resetForTesting();
		Object schedulerState = createSchedulerState();
		Class<?> schedulerClass = CoreDispatchBatchScheduler.class;
		Class<?> schedulerStateClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$SchedulerState");
		Class<?> accumulatorClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchAccumulator");
		Method acquireAccumulator = schedulerStateClass.getDeclaredMethod(
			"acquireAccumulator",
			ActivatableTargetBlockEntity.class
		);
		acquireAccumulator.setAccessible(true);
		Method recycleAccumulator = schedulerStateClass.getDeclaredMethod("recycleAccumulator", accumulatorClass);
		recycleAccumulator.setAccessible(true);
		Field retainedCapField = schedulerClass.getDeclaredField("MAX_RETAINED_ACCUMULATORS");
		retainedCapField.setAccessible(true);
		int retainedCap = retainedCapField.getInt(null);

		List<Object> acquiredAccumulators = new ArrayList<>();
		for (int index = 0; index < retainedCap + 5; index++) {
			acquiredAccumulators.add(acquireAccumulator.invoke(schedulerState, createTarget()));
		}
		for (Object accumulator : acquiredAccumulators) {
			recycleAccumulator.invoke(schedulerState, accumulator);
		}

		Field accumulatorPoolField = schedulerStateClass.getDeclaredField("accumulatorPool");
		accumulatorPoolField.setAccessible(true);
		List<?> accumulatorPool = (List<?>) accumulatorPoolField.get(schedulerState);
		assertEquals(retainedCap, accumulatorPool.size());
	}

	/**
	 * scheduler 空闲一段时间后应释放 retained pool，避免状态永久挂在服务端缓存中。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void onEndServerTickShouldReleaseIdleStateAfterThreshold() throws Exception {
		CoreDispatchBatchScheduler.resetForTesting();
		Class<?> schedulerStateClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$SchedulerState");
		Class<?> accumulatorClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchAccumulator");
		Method acquireAccumulator = schedulerStateClass.getDeclaredMethod(
			"acquireAccumulator",
			ActivatableTargetBlockEntity.class
		);
		acquireAccumulator.setAccessible(true);
		Method recycleAccumulator = schedulerStateClass.getDeclaredMethod("recycleAccumulator", accumulatorClass);
		recycleAccumulator.setAccessible(true);
		Object schedulerState = createSchedulerState();
		Object accumulator = acquireAccumulator.invoke(schedulerState, createTarget());
		recycleAccumulator.invoke(schedulerState, accumulator);

		Field stateByServerField = CoreDispatchBatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		stateByServerField.setAccessible(true);
		Map<MinecraftServer, Object> stateByServer = (Map<MinecraftServer, Object>) stateByServerField.get(null);
		stateByServer.put(null, schedulerState);

		Field idleReleaseField = CoreDispatchBatchScheduler.class.getDeclaredField("MAX_IDLE_TICKS_BEFORE_POOL_RELEASE");
		idleReleaseField.setAccessible(true);
		int idleReleaseTicks = idleReleaseField.getInt(null);
		Method onEndServerTick = CoreDispatchBatchScheduler.class.getDeclaredMethod("onEndServerTick", MinecraftServer.class);
		onEndServerTick.setAccessible(true);

		for (int index = 1; index < idleReleaseTicks; index++) {
			onEndServerTick.invoke(null, new Object[] { null });
			assertTrue(stateByServer.containsKey(null));
		}

		onEndServerTick.invoke(null, new Object[] { null });
		assertFalse(stateByServer.containsKey(null));
	}

	/**
	 * 不同 `dueTick` 的条目应保留在不同 bucket，避免后到条目借用首条窗口。
	 */
	@Test
	void mergeAllShouldKeepSeparateDueTickBucketsForSameTarget() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, 201L, List.of(createSyncEntry(41L, 1L, 9))));
		assertTrue(invokeMergeAll(accumulator, 202L, List.of(createSyncEntry(41L, 2L, 13))));

		Map<?, ?> bucketsByDueTick = getBucketsByDueTick(accumulator);
		assertEquals(2, bucketsByDueTick.size());
		assertEquals(1, getEntriesBySourceAndKind(accumulator, 201L).size());
		assertEquals(1, getEntriesBySourceAndKind(accumulator, 202L).size());
	}

	/**
	 * `dueTick` 未到时不应 flush，到期后才允许 flush。
	 */
	@Test
	void flushDueBucketsShouldDelayUntilDueTick() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, 210L, List.of(createSyncEntry(51L, 1L, 12))));

		invokeFlushDueBuckets(accumulator, 209L, false);
		assertEquals(1, getBucketsByDueTick(accumulator).size());
		invokeFlushDueBuckets(accumulator, 210L, false);
		assertTrue(getBucketsByDueTick(accumulator).isEmpty());
	}

	/**
	 * 同一目标存在多个未来 bucket 时，当前 tick 只应 flush 已到期 bucket。
	 */
	@Test
	void flushDueBucketsShouldOnlyFlushDueBucketsUpToCurrentTick() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, 301L, List.of(createSyncEntry(61L, 1L, 15))));
		assertTrue(invokeMergeAll(accumulator, 302L, List.of(createSyncEntry(62L, 2L, 7))));

		invokeFlushDueBuckets(accumulator, 301L, false);
		assertFalse(getBucketsByDueTick(accumulator).containsKey(301L));
		assertTrue(getBucketsByDueTick(accumulator).containsKey(302L));
	}

	/**
	 * late arrival 补 flush 只应在 `window=0` 且当前 tick 的 END 已完成时触发。
	 */
	@Test
	void shouldFlushLateArrivalsShouldOnlyAllowWindowZeroAfterEndTick() throws Exception {
		assertTrue(invokeShouldFlushLateArrivals(400L, 0, 400L));
		assertFalse(invokeShouldFlushLateArrivals(400L, 1, 400L));
		assertFalse(invokeShouldFlushLateArrivals(401L, 0, 400L));
		assertFalse(invokeShouldFlushLateArrivals(400L, 0, null));
	}

	/**
	 * 当 `window=0` 且当前 tick 的 END 已经过去后，新入队 batch 应被立刻补 flush。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void flushLateArrivalsIfCurrentTickEndAlreadyPassedShouldFlushPendingBatchWhenWindowZero() throws Exception {
		CoreDispatchBatchScheduler.resetForTesting();
		Object schedulerState = createSchedulerState();
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, 0L, List.of(createSyncEntry(71L, 1L, 15))));

		Field stateByServerField = CoreDispatchBatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		stateByServerField.setAccessible(true);
		Map<MinecraftServer, Object> stateByServer = (Map<MinecraftServer, Object>) stateByServerField.get(null);
		stateByServer.put(null, schedulerState);

		Field pendingByTargetField = schedulerState.getClass().getDeclaredField("pendingByTarget");
		pendingByTargetField.setAccessible(true);
		Map<Object, Object> pendingByTarget = (Map<Object, Object>) pendingByTargetField.get(schedulerState);
		pendingByTarget.put(createTargetBatchKey(1L), accumulator);

		setLastCompletedEndTick(null, 0L);
		invokeFlushLateArrivalsIfCurrentTickEndAlreadyPassed(null, 0L);

		assertTrue(pendingByTarget.isEmpty());

		Field accumulatorPoolField = schedulerState.getClass().getDeclaredField("accumulatorPool");
		accumulatorPoolField.setAccessible(true);
		List<?> accumulatorPool = (List<?>) accumulatorPoolField.get(schedulerState);
		assertEquals(1, accumulatorPool.size());
	}

	private static Object createAccumulator() throws Exception {
		Class<?> accumulatorClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchAccumulator");
		Constructor<?> constructor = accumulatorClass.getDeclaredConstructor(ActivatableTargetBlockEntity.class);
		constructor.setAccessible(true);
		return constructor.newInstance(createTarget());
	}

	private static Object createSchedulerState() throws Exception {
		Class<?> schedulerStateClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$SchedulerState");
		Constructor<?> constructor = schedulerStateClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static ActivatableTargetBlockEntity.DispatchBatchEntry createSyncEntry(long sourceSerial, long seq, int syncSignalStrength) {
		return new ActivatableTargetBlockEntity.DispatchBatchEntry(
			ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
			ActivatableTargetBlockEntity.DeltaAction.UPSERT,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			ActivationMode.TOGGLE,
			syncSignalStrength,
			ActivatableTargetBlockEntity.EventMeta.of(40L, 0, seq)
		);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, ?> getBucketsByDueTick(Object accumulator) throws Exception {
		Field field = accumulator.getClass().getDeclaredField("bucketsByDueTick");
		field.setAccessible(true);
		return (Map<Long, ?>) field.get(accumulator);
	}

	@SuppressWarnings("unchecked")
	private static Map<?, ?> getEntriesBySourceAndKind(Object accumulator, long dueTick) throws Exception {
		Object bucket = getBucketsByDueTick(accumulator).get(dueTick);
		if (bucket == null) {
			return Map.of();
		}
		Field field = bucket.getClass().getDeclaredField("entriesBySourceAndKind");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(bucket);
	}

	private static boolean invokeMergeAll(
		Object accumulator,
		long dueTick,
		List<ActivatableTargetBlockEntity.DispatchBatchEntry> batchEntries
	) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("mergeAll", long.class, List.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(accumulator, dueTick, batchEntries);
	}

	private static void invokeFlushDueBuckets(Object accumulator, long currentTick, boolean forceFlush) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("flushDueBuckets", long.class, boolean.class);
		method.setAccessible(true);
		method.invoke(accumulator, currentTick, forceFlush);
	}

	private static boolean invokeShouldFlushLateArrivals(long currentTick, int windowTicks, Long lastCompletedEndTick)
		throws Exception {
		Method method = CoreDispatchBatchScheduler.class.getDeclaredMethod(
			"shouldFlushLateArrivals",
			long.class,
			int.class,
			Long.class
		);
		method.setAccessible(true);
		return (Boolean) method.invoke(null, currentTick, windowTicks, lastCompletedEndTick);
	}

	private static void invokeFlushLateArrivalsIfCurrentTickEndAlreadyPassed(MinecraftServer server, long currentTick)
		throws Exception {
		Method method = CoreDispatchBatchScheduler.class.getDeclaredMethod(
			"flushLateArrivalsIfCurrentTickEndAlreadyPassed",
			MinecraftServer.class,
			long.class
		);
		method.setAccessible(true);
		method.invoke(null, server, currentTick);
	}

	@SuppressWarnings("unchecked")
	private static void setLastCompletedEndTick(MinecraftServer server, long tick) throws Exception {
		Field field = CoreDispatchBatchScheduler.class.getDeclaredField("LAST_COMPLETED_END_TICK_BY_SERVER");
		field.setAccessible(true);
		Map<MinecraftServer, Long> lastCompletedByServer = (Map<MinecraftServer, Long>) field.get(null);
		lastCompletedByServer.put(server, tick);
	}

	private static Object createTargetBatchKey(long targetSerial) throws Exception {
		Class<?> keyClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchKey");
		Constructor<?> constructor = keyClass.getDeclaredConstructor(
			net.minecraft.resources.ResourceKey.class,
			BlockPos.class,
			LinkNodeType.class,
			long.class
		);
		constructor.setAccessible(true);
		return constructor.newInstance(Level.OVERWORLD, BlockPos.ZERO, LinkNodeType.CORE, targetSerial);
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>) type;
	}

	private static TestTargetEntity createTarget() {
		return new TestTargetEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
	}

	/**
	 * scheduler 聚合测试使用的最小 `core` 实体。
	 */
	private static final class TestTargetEntity extends ActivatableTargetBlockEntity {
		private TestTargetEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		protected void onActiveChanged(boolean active) {}

		@Override
		protected void syncBlockStateFromDerivedState(boolean active) {}

		@Override
		protected boolean shouldQueueLoadBlockStateSync(boolean active) {
			return false;
		}

		@Override
		protected void schedulePulseReset(int pulseTicks) {}

		@Override
		protected LinkNodeType getNodeType() {
			return LinkNodeType.CORE;
		}
	}
}
