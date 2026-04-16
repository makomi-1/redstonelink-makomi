package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.PairableNodeBlockEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 频道模式派发中间收口调度测试。
 */
@Tag("stable-core")
class ChannelDispatchSchedulerTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void resetSchedulerStateAfterEach() {
		ServerThreadGuard.resetForTesting();
		ChannelDispatchScheduler.resetForTesting();
		CoreDispatchBatchScheduler.resetForTesting();
	}

	/**
	 * 频道中间层的 late-arrival 补 flush 只应在“当前 tick 的 END 已完成”时触发。
	 */
	@Test
	void shouldFlushLateArrivalsShouldOnlyAllowSameTickAfterEndTick() throws Exception {
		Method method = ChannelDispatchScheduler.class.getDeclaredMethod("shouldFlushLateArrivals", long.class, Long.class);
		method.setAccessible(true);

		assertTrue((boolean) method.invoke(null, 120L, 120L));
		assertFalse((boolean) method.invoke(null, 121L, 120L));
		assertFalse((boolean) method.invoke(null, 120L, null));
	}

	/**
	 * 同频道同 tick 的 loaded direct 条目应先进入频道桶，再统一转发到 `core` 批调度器。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void flushPendingForTestingShouldForwardMergedEntriesIntoCoreBatchScheduler() throws Exception {
		ServerThreadGuard.setSameThreadProbeForTesting(server -> true);
		MinecraftServer server = TestMinecraftServerFactory.newDummyServer();
		TestTargetEntity target = createTarget();
		setTargetSerial(target, 301L);
		attachDummyServerLevel(target, 0L);

		ChannelDispatchScheduler.enqueueLoadedChannelDispatch(
			server,
			7L,
			4L,
			List.of(new ChannelDispatchScheduler.LoadedChannelTarget(target, LinkNodeType.CORE, 301L)),
			createSyncEntry(11L, 1L, 5)
		);
		ChannelDispatchScheduler.enqueueLoadedChannelDispatch(
			server,
			7L,
			4L,
			List.of(new ChannelDispatchScheduler.LoadedChannelTarget(target, LinkNodeType.CORE, 301L)),
			createSyncEntry(11L, 2L, 12)
		);

		ChannelDispatchScheduler.flushPendingForTesting(server);

		Field channelStateField = ChannelDispatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		channelStateField.setAccessible(true);
		Map<MinecraftServer, ?> channelStateByServer = (Map<MinecraftServer, ?>) channelStateField.get(null);
		assertFalse(channelStateByServer.containsKey(server));

		Field coreStateField = CoreDispatchBatchScheduler.class.getDeclaredField("STATE_BY_SERVER");
		coreStateField.setAccessible(true);
		Map<MinecraftServer, ?> coreStateByServer = (Map<MinecraftServer, ?>) coreStateField.get(null);
		Object schedulerState = coreStateByServer.get(server);
		assertTrue(schedulerState != null);

		Field pendingByTargetField = schedulerState.getClass().getDeclaredField("pendingByTarget");
		pendingByTargetField.setAccessible(true);
		Map<?, ?> pendingByTarget = (Map<?, ?>) pendingByTargetField.get(schedulerState);
		assertEquals(1, pendingByTarget.size());

		Object accumulator = pendingByTarget.values().iterator().next();
		Map<?, ?> dueTickBuckets = getBucketsByDueTick(accumulator);
		assertEquals(1, dueTickBuckets.size());

		Map<?, ?> entries = getEntriesBySourceAndKind(accumulator, 0L);
		assertEquals(1, entries.size());
		ActivatableTargetBlockEntity.DispatchBatchEntry mergedEntry =
			(ActivatableTargetBlockEntity.DispatchBatchEntry) entries.values().iterator().next();
		assertEquals(12, mergedEntry.syncSignalStrength());
		assertEquals(2L, mergedEntry.eventMeta().seq());
	}

	private static TestTargetEntity createTarget() {
		return new TestTargetEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
	}

	private static void setTargetSerial(ActivatableTargetBlockEntity target, long serial) throws Exception {
		Field serialField = PairableNodeBlockEntity.class.getDeclaredField("serial");
		serialField.setAccessible(true);
		serialField.setLong(target, serial);
	}

	private static ActivatableTargetBlockEntity.DispatchBatchEntry createSyncEntry(
		long sourceSerial,
		long seq,
		int syncSignalStrength
	) {
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

	private static Map<?, ?> getBucketsByDueTick(Object accumulator) throws Exception {
		Field field = accumulator.getClass().getDeclaredField("bucketsByDueTick");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(accumulator);
	}

	private static Map<?, ?> getEntriesBySourceAndKind(Object accumulator, long dueTick) throws Exception {
		Object bucket = getBucketsByDueTick(accumulator).get(dueTick);
		if (bucket == null) {
			return Map.of();
		}
		Field field = bucket.getClass().getDeclaredField("entriesBySourceAndKind");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(bucket);
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends PairableNodeBlockEntity>) type;
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
			Object levelDataProxy = java.lang.reflect.Proxy.newProxyInstance(
				ChannelDispatchSchedulerTest.class.getClassLoader(),
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
	 * 频道调度测试使用的最小 `core` 实体。
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
}
