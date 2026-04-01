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
	 * 批量 merge 时，同源同 kind 应只保留时间键更新的一条。
	 */
	@Test
	void mergeAllShouldKeepLatestEntryPerSourceAndKind() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(
			invokeMergeAll(
				accumulator,
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

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator);
		assertEquals(1, entries.size());
		ActivatableTargetBlockEntity.DispatchBatchEntry mergedEntry =
			(ActivatableTargetBlockEntity.DispatchBatchEntry) entries.values().iterator().next();
		assertEquals(12, mergedEntry.syncSignalStrength());
		assertEquals(2L, mergedEntry.eventMeta().seq());
	}

	/**
	 * 完整 invalidation 应覆盖窗口内更早的 sync / source invalidation / chunk-unload invalidation。
	 */
	@Test
	void mergeAllShouldDropCoveredEntriesWhenFullInvalidationArrives() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(
			invokeMergeAll(
				accumulator,
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

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator);
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
	 * 窗口为 0 时应保持当前 tick 可 flush，等价于现有语义。
	 */
	@Test
	void isFlushDueShouldFlushImmediatelyWhenWindowTicksIsZero() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, List.of(createSyncEntry(41L, 1L, 9))));

		invokeOpenWindowIfNeeded(accumulator, 100L);
		assertTrue(invokeIsFlushDue(accumulator, 100L, 0));
	}

	/**
	 * 窗口为 1 时首次 tick 不 flush，下一 tick 才允许 flush。
	 */
	@Test
	void isFlushDueShouldDelayUntilNextTickWhenWindowTicksIsOne() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, List.of(createSyncEntry(51L, 1L, 12))));

		invokeOpenWindowIfNeeded(accumulator, 200L);
		assertFalse(invokeIsFlushDue(accumulator, 200L, 1));
		assertTrue(invokeIsFlushDue(accumulator, 201L, 1));
	}

	/**
	 * 同一目标在窗口内重复 merge 时，不应把窗口起点不断后推。
	 */
	@Test
	void openWindowIfNeededShouldKeepOriginalWindowStartTick() throws Exception {
		Object accumulator = createAccumulator();
		assertTrue(invokeMergeAll(accumulator, List.of(createSyncEntry(61L, 1L, 15))));

		invokeOpenWindowIfNeeded(accumulator, 300L);
		invokeOpenWindowIfNeeded(accumulator, 305L);

		assertEquals(300L, getWindowStartTick(accumulator));
		assertTrue(invokeIsFlushDue(accumulator, 301L, 1));
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
	private static Map<?, ?> getEntriesBySourceAndKind(Object accumulator) throws Exception {
		Field field = accumulator.getClass().getDeclaredField("entriesBySourceAndKind");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(accumulator);
	}

	private static long getWindowStartTick(Object accumulator) throws Exception {
		Field field = accumulator.getClass().getDeclaredField("windowStartTick");
		field.setAccessible(true);
		return field.getLong(accumulator);
	}

	private static boolean invokeMergeAll(
		Object accumulator,
		List<ActivatableTargetBlockEntity.DispatchBatchEntry> batchEntries
	) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("mergeAll", List.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(accumulator, batchEntries);
	}

	private static void invokeOpenWindowIfNeeded(Object accumulator, long currentTick) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("openWindowIfNeeded", long.class);
		method.setAccessible(true);
		method.invoke(accumulator, currentTick);
	}

	private static boolean invokeIsFlushDue(Object accumulator, long currentTick, int windowTicks) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("isFlushDue", long.class, int.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(accumulator, currentTick, windowTicks);
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
