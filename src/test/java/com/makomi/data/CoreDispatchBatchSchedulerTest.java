package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
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

	private static Object createAccumulator() throws Exception {
		Class<?> accumulatorClass = Class.forName("com.makomi.data.CoreDispatchBatchScheduler$TargetBatchAccumulator");
		Constructor<?> constructor = accumulatorClass.getDeclaredConstructor(ActivatableTargetBlockEntity.class);
		constructor.setAccessible(true);
		return constructor.newInstance(createTarget());
	}

	@SuppressWarnings("unchecked")
	private static Map<?, ?> getEntriesBySourceAndKind(Object accumulator) throws Exception {
		Field field = accumulator.getClass().getDeclaredField("entriesBySourceAndKind");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(accumulator);
	}

	private static boolean invokeMergeAll(
		Object accumulator,
		List<ActivatableTargetBlockEntity.DispatchBatchEntry> batchEntries
	) throws Exception {
		Method method = accumulator.getClass().getDeclaredMethod("mergeAll", List.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(accumulator, batchEntries);
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
