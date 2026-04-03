package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

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
	 * 同一 `dueTick` bucket 内，同源 `toggle/pulse` 应分别保留，避免互相覆盖。
	 */
	@Test
	void mergeAllShouldKeepActivationEntriesSeparatedByModeWithinSameDueTickBucket() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(
			invokeMergeAll(
				accumulator,
				125L,
				List.of(
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						21L,
						ActivationMode.TOGGLE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(21L, 0, 1L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						21L,
						ActivationMode.PULSE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(21L, 0, 2L)
					)
				)
			)
		);

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator, 125L);
		assertEquals(2, entries.size());
		assertTrue(
			entries
				.values()
				.stream()
				.map(ActivatableTargetBlockEntity.DispatchBatchEntry.class::cast)
				.anyMatch(entry -> entry.activationMode() == ActivationMode.TOGGLE)
		);
		assertTrue(
			entries
				.values()
				.stream()
				.map(ActivatableTargetBlockEntity.DispatchBatchEntry.class::cast)
				.anyMatch(entry -> entry.activationMode() == ActivationMode.PULSE)
		);
	}

	/**
	 * 同一 `dueTick` bucket 内，完整 invalidation 应覆盖更早的 sync / activation / 局部 invalidation。
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
						ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
						ActivatableTargetBlockEntity.DeltaAction.UPSERT,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.PULSE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 2L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION,
						ActivatableTargetBlockEntity.DeltaAction.REMOVE,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.TOGGLE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 3L)
					),
					new ActivatableTargetBlockEntity.DispatchBatchEntry(
						ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
						ActivatableTargetBlockEntity.DeltaAction.REMOVE,
						LinkNodeType.TRIGGER_SOURCE,
						31L,
						ActivationMode.TOGGLE,
						0,
						ActivatableTargetBlockEntity.EventMeta.of(30L, 0, 4L)
					)
				)
			)
		);

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator, 130L);
		assertEquals(1, entries.size());
		ActivatableTargetBlockEntity.DispatchBatchEntry mergedEntry =
			(ActivatableTargetBlockEntity.DispatchBatchEntry) entries.values().iterator().next();
		assertEquals(ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION, mergedEntry.deltaKind());
		assertEquals(4L, mergedEntry.eventMeta().seq());
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
	 * 两阶段 flush 应先把 bucket 从 accumulator 脱离，再交给外层 apply。
	 */
	@Test
	void detachDueBucketsShouldRemoveBucketsBeforeApply() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, 320L, List.of(createSyncEntry(66L, 1L, 15))));

		Object flushPlan = invokeDetachDueBuckets(accumulator, 320L, false);
		assertTrue(getBucketsByDueTick(accumulator).isEmpty());
		assertTrue(flushPlan != null);
	}

	/**
	 * flush 期间若下游逻辑重入修改 `pendingByTarget`，不应再触发 map 迭代并发修改异常。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void flushServerBatchesShouldTolerateReentrantPendingMutationDuringApplyPhase() throws Exception {
		CoreDispatchBatchScheduler.resetForTesting();
		Object schedulerState = createSchedulerState();

		Field pendingByTargetField = schedulerState.getClass().getDeclaredField("pendingByTarget");
		pendingByTargetField.setAccessible(true);
		Map<Object, Object> pendingByTarget = (Map<Object, Object>) pendingByTargetField.get(schedulerState);

		ReentrantMutationTargetEntity target = new ReentrantMutationTargetEntity(pendingByTarget);
		attachDummyServerLevel(target, 0L);
		Object accumulator = createAccumulator(target);
		assertTrue(invokeMergeAll(accumulator, 0L, List.of(createSyncEntry(81L, 1L, 15))));
		pendingByTarget.put(createTargetBatchKey(1L), accumulator);

		Field stateByServerField = CoreDispatchBatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		stateByServerField.setAccessible(true);
		Map<MinecraftServer, Object> stateByServer = (Map<MinecraftServer, Object>) stateByServerField.get(null);
		stateByServer.put(null, schedulerState);

		assertDoesNotThrow(() -> invokeFlushServerBatches(null, false));
		assertTrue(target.reentered());
		assertEquals(1, pendingByTarget.size());
		assertTrue(pendingByTarget.containsKey(createTargetBatchKey(2L)));
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

	/**
	 * apply phase 内再次请求 late-arrival 补 flush 时，应转为外层循环 drain，而不是递归进入下一层 apply。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void flushLateArrivalsDuringApplyPhaseShouldDrainDeferredRequestsWithoutRecursiveApplyDepth() throws Exception {
		CoreDispatchBatchScheduler.resetForTesting();
		Object schedulerState = createSchedulerState();

		Field pendingByTargetField = schedulerState.getClass().getDeclaredField("pendingByTarget");
		pendingByTargetField.setAccessible(true);
		Map<Object, Object> pendingByTarget = (Map<Object, Object>) pendingByTargetField.get(schedulerState);

		LateArrivalFlushChain chain = new LateArrivalFlushChain(pendingByTarget, null, 0L, 4L);
		ChainedLateArrivalTargetEntity target = chain.createTarget(1L);
		attachDummyServerLevel(target, 0L);
		Object accumulator = createAccumulator(target);
		assertTrue(invokeMergeAll(accumulator, 0L, List.of(createSyncEntry(91L, 1L, 15))));
		pendingByTarget.put(createTargetBatchKey(1L), accumulator);

		Field stateByServerField = CoreDispatchBatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		stateByServerField.setAccessible(true);
		Map<MinecraftServer, Object> stateByServer = (Map<MinecraftServer, Object>) stateByServerField.get(null);
		stateByServer.put(null, schedulerState);
		setLastCompletedEndTick(null, 0L);

		assertDoesNotThrow(() -> invokeFlushServerBatches(null, false));
		assertEquals(4, chain.appliedTargetCount());
		assertEquals(3, chain.lateFlushRequestCount());
		assertEquals(1, chain.maxCallbackDepth());
		assertTrue(pendingByTarget.isEmpty());
	}

	private static Object createAccumulator() throws Exception {
		return createAccumulator(createTarget());
	}

	private static Object createAccumulator(ActivatableTargetBlockEntity targetBlockEntity) throws Exception {
		Class<?> accumulatorClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchAccumulator");
		Constructor<?> constructor = accumulatorClass.getDeclaredConstructor(ActivatableTargetBlockEntity.class);
		constructor.setAccessible(true);
		return constructor.newInstance(targetBlockEntity);
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

	private static Object invokeDetachDueBuckets(Object accumulator, long currentTick, boolean forceFlush) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("detachDueBuckets", long.class, boolean.class);
		method.setAccessible(true);
		return method.invoke(accumulator, currentTick, forceFlush);
	}

	private static void invokeFlushServerBatches(MinecraftServer server, boolean forceFlush) throws Exception {
		Method method = CoreDispatchBatchScheduler.class.getDeclaredMethod("flushServerBatches", MinecraftServer.class, boolean.class);
		method.setAccessible(true);
		method.invoke(null, server, forceFlush);
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

	private static void attachDummyServerLevel(ActivatableTargetBlockEntity target, long gameTime) throws Exception {
		Field levelField = BlockEntity.class.getDeclaredField("level");
		levelField.setAccessible(true);
		levelField.set(target, dummyServerLevel(gameTime));
	}

	private static ServerLevel dummyServerLevel(long gameTime) {
		try {
			Unsafe unsafe = unsafe();
			ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
			Field clientSideField = Level.class.getDeclaredField("isClientSide");
			clientSideField.setAccessible(true);
			clientSideField.setBoolean(level, false);
			Field levelDataField = Level.class.getDeclaredField("levelData");
			levelDataField.setAccessible(true);
			Class<?> levelDataType = levelDataField.getType();
			Object levelDataProxy = Proxy.newProxyInstance(
				CoreDispatchBatchSchedulerTest.class.getClassLoader(),
				new Class<?>[] { levelDataType },
				(proxy, method, args) -> switch (method.getName()) {
					case "getGameTime", "getDayTime" -> gameTime;
					case "isHardcore", "isFlatWorld" -> false;
					case "getClearWeatherTime", "getRainTime", "getThunderTime", "getSpawnAngle" -> 0;
					default -> defaultValue(method.getReturnType());
				}
			);
			levelDataField.set(level, levelDataProxy);
			return level;
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to allocate dummy ServerLevel", ex);
		}
	}

	private static Unsafe unsafe() throws ReflectiveOperationException {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	private static Object defaultValue(Class<?> type) {
		if (type == null || !type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0F;
		}
		if (type == double.class) {
			return 0D;
		}
		if (type == char.class) {
			return '\0';
		}
		return null;
	}

	/**
	 * scheduler 聚合测试使用的最小 `core` 实体。
	 */
	private static class TestTargetEntity extends ActivatableTargetBlockEntity {
		private TestTargetEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		protected void onActiveChanged(boolean active) {}

		@Override
		protected void syncBlockStateFromDerivedState(boolean active) {}

		@Override
		public void setChanged() {}

		@Override
		protected void syncToClient() {}

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

	/**
	 * flush apply 阶段内模拟“下游又把新 dispatch 重新写回 scheduler”。
	 */
	private static final class ReentrantMutationTargetEntity extends TestTargetEntity {
		private final Map<Object, Object> pendingByTarget;
		private boolean reentered;

		private ReentrantMutationTargetEntity(Map<Object, Object> pendingByTarget) {
			super(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
			this.pendingByTarget = pendingByTarget;
		}

		@Override
		protected void onActiveChanged(boolean active) {
			if (!active || reentered) {
				return;
			}
			reentered = true;
			try {
				Object accumulator = createAccumulator();
				invokeMergeAll(accumulator, 99L, List.of(createSyncEntry(82L, 2L, 9)));
				pendingByTarget.put(createTargetBatchKey(2L), accumulator);
			} catch (Exception ex) {
				throw new IllegalStateException("failed to enqueue reentrant pending batch", ex);
			}
		}

		private boolean reentered() {
			return reentered;
		}
	}

	/**
	 * 通过链式 late-arrival 请求观察 apply 回调栈深度，验证补 flush 已转为外层循环 drain。
	 */
	private static final class LateArrivalFlushChain {
		private final Map<Object, Object> pendingByTarget;
		private final MinecraftServer server;
		private final long currentTick;
		private final long maxTargetSerial;
		private int callbackDepth;
		private int maxCallbackDepth;
		private int appliedTargetCount;
		private int lateFlushRequestCount;

		private LateArrivalFlushChain(
			Map<Object, Object> pendingByTarget,
			MinecraftServer server,
			long currentTick,
			long maxTargetSerial
		) {
			this.pendingByTarget = pendingByTarget;
			this.server = server;
			this.currentTick = currentTick;
			this.maxTargetSerial = maxTargetSerial;
		}

		private ChainedLateArrivalTargetEntity createTarget(long targetSerial) {
			return new ChainedLateArrivalTargetEntity(targetSerial, this);
		}

		private void onActive(long targetSerial) {
			callbackDepth++;
			maxCallbackDepth = Math.max(maxCallbackDepth, callbackDepth);
			appliedTargetCount++;
			try {
				long nextTargetSerial = targetSerial + 1L;
				if (nextTargetSerial > maxTargetSerial) {
					return;
				}
				lateFlushRequestCount++;
				ChainedLateArrivalTargetEntity nextTarget = createTarget(nextTargetSerial);
				attachDummyServerLevel(nextTarget, currentTick);
				Object accumulator = createAccumulator(nextTarget);
				invokeMergeAll(
					accumulator,
					currentTick,
					List.of(createSyncEntry(90L + nextTargetSerial, nextTargetSerial, 9))
				);
				pendingByTarget.put(createTargetBatchKey(nextTargetSerial), accumulator);
				invokeFlushLateArrivalsIfCurrentTickEndAlreadyPassed(server, currentTick);
			} catch (Exception ex) {
				throw new IllegalStateException("failed to enqueue late arrival chain", ex);
			} finally {
				callbackDepth--;
			}
		}

		private int maxCallbackDepth() {
			return maxCallbackDepth;
		}

		private int appliedTargetCount() {
			return appliedTargetCount;
		}

		private int lateFlushRequestCount() {
			return lateFlushRequestCount;
		}
	}

	/**
	 * 每次变为 active 时继续追加一个 late-arrival 目标，构造链式补 flush 场景。
	 */
	private static final class ChainedLateArrivalTargetEntity extends TestTargetEntity {
		private final long targetSerial;
		private final LateArrivalFlushChain chain;

		private ChainedLateArrivalTargetEntity(long targetSerial, LateArrivalFlushChain chain) {
			super(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
			this.targetSerial = targetSerial;
			this.chain = chain;
		}

		@Override
		protected void onActiveChanged(boolean active) {
			if (!active) {
				return;
			}
			chain.onActive(targetSerial);
		}
	}
}
