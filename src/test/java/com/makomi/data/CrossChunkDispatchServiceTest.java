package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfigTestHelper;
import com.makomi.testsupport.TestMinecraftSupport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/**
 * CrossChunkDispatchService 参数守卫与状态机测试。
 */
@Tag("stable-core")
class CrossChunkDispatchServiceTest {
	@BeforeAll
	static void bootstrapRegistries() {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	@AfterEach
	void resetThreadGuardAfterEach() {
		ServerThreadGuard.resetForTesting();
	}

	/**
	 * register 入口应可重复调用（仅注册回调，不抛异常）。
	 */
	@Test
	void registerShouldBeCallable() {
		CrossChunkDispatchService.register();
		CrossChunkDispatchService.register();
	}

	/**
	 * 非主线程访问全局 dispatch 状态时应直接失败，避免静默污染服务端状态缓存。
	 */
	@Test
	void getOrCreateStateShouldRejectOffThreadAccess() {
		ServerThreadGuard.setSameThreadProbeForTesting(server -> false);
		MinecraftServer server = TestMinecraftServerFactory.newDummyServer();

		IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() -> CrossChunkDispatchService.getOrCreateState(server)
		);
		assertEquals(
			"Operation must run on the server thread: crosschunk dispatch state access",
			exception.getMessage()
		);
	}

	/**
	 * queueActivation 在 activationMode 为空时应直接拒绝。
	 */
	@Test
	void queueActivationShouldRejectWhenActivationModeIsNull() {
		CrossChunkDispatchService.QueueResult result = CrossChunkDispatchService.queueActivation(
			null,
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			null
		);
		assertFalse(result.accepted());
		assertFalse(result.forceLoadPlanned());
	}

	/**
	 * pulse/toggle 应拆分到独立的事件 kind，并保持普通 relay 默认关闭。
	 */
	@Test
	void resolveActivationQueuePolicyShouldSplitKindsByMode() throws Exception {
		Method resolvePolicy = CrossChunkDispatchService.class.getDeclaredMethod("resolveActivationQueuePolicy", ActivationMode.class);
		resolvePolicy.setAccessible(true);

		Object pulsePolicy = resolvePolicy.invoke(null, ActivationMode.PULSE);
		Object togglePolicy = resolvePolicy.invoke(null, ActivationMode.TOGGLE);

		Method dispatchKindAccessor = pulsePolicy.getClass().getDeclaredMethod("dispatchKind");
		dispatchKindAccessor.setAccessible(true);
		Method ttlRelayEnabledAccessor = pulsePolicy.getClass().getDeclaredMethod("ttlRelayEnabled");
		ttlRelayEnabledAccessor.setAccessible(true);
		Method persistentExperimentalAccessor = pulsePolicy.getClass().getDeclaredMethod("persistentExperimental");
		persistentExperimentalAccessor.setAccessible(true);
		Method ttlTicksAccessor = pulsePolicy.getClass().getDeclaredMethod("ttlTicks");
		ttlTicksAccessor.setAccessible(true);

		assertEquals(CrossChunkDispatchQueueSavedData.DispatchKind.PULSE_EVENT, dispatchKindAccessor.invoke(pulsePolicy));
		assertFalse((boolean) ttlRelayEnabledAccessor.invoke(pulsePolicy));
		assertFalse((boolean) persistentExperimentalAccessor.invoke(pulsePolicy));
		assertEquals(200, ttlTicksAccessor.invoke(pulsePolicy));

		assertEquals(CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT, dispatchKindAccessor.invoke(togglePolicy));
		assertFalse((boolean) ttlRelayEnabledAccessor.invoke(togglePolicy));
		assertFalse((boolean) persistentExperimentalAccessor.invoke(togglePolicy));
		assertEquals(200, ttlTicksAccessor.invoke(togglePolicy));
	}

	/**
	 * 实验性 activation 持久化开启后，事件 TTL 应提升为不限时。
	 */
	@Test
	void resolveActivationTtlTicksShouldUseUnlimitedWhenExperimentalEnabled() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.activation.pulse.persistentExperimental", "true");
		properties.setProperty("crosschunk.activation.toggle.persistentExperimental", "true");

		withCrossChunkConfig(properties, () -> {
			Method resolvePolicy = CrossChunkDispatchService.class.getDeclaredMethod("resolveActivationQueuePolicy", ActivationMode.class);
			resolvePolicy.setAccessible(true);
			Class<?> policyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ActivationQueuePolicy");
			Method resolveTtl = CrossChunkDispatchService.class.getDeclaredMethod("resolveActivationTtlTicks", policyClass);
			resolveTtl.setAccessible(true);

			assertEquals(Long.MAX_VALUE, resolveTtl.invoke(null, resolvePolicy.invoke(null, ActivationMode.PULSE)));
			assertEquals(Long.MAX_VALUE, resolveTtl.invoke(null, resolvePolicy.invoke(null, ActivationMode.TOGGLE)));
		});
	}

	/**
	 * activation 已转为事件语义后，离线 REMOVE 应保持拒绝，避免旧回滚路径误生效。
	 */
	@Test
	void queueActivationRemoveShouldRejectEventSemanticReplay() {
		CrossChunkDispatchService.QueueResult result = CrossChunkDispatchService.queueActivationRemove(
			null,
			null,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			ActivationMode.TOGGLE,
			0L,
			0
		);
		assertFalse(result.accepted());
		assertFalse(result.forceLoadPlanned());
	}

	/**
	 * queueDispatch 私有守卫在非法参数时应拒绝。
	 */
	@Test
	void queueDispatchShouldRejectInvalidInputs() throws Exception {
		Method queueDispatch = CrossChunkDispatchService.class.getDeclaredMethod(
			"queueDispatch",
			ServerLevel.class,
			LinkSavedData.LinkNode.class,
			LinkNodeType.class,
			long.class,
			Class.forName("com.makomi.data.CrossChunkDispatchQueueSavedData$DispatchKind"),
			Class.forName("com.makomi.data.CrossChunkDispatchQueueSavedData$DispatchAction"),
			ActivationMode.class,
			int.class,
			long.class,
			int.class,
			long.class
		);
		queueDispatch.setAccessible(true);

		Class<?> dispatchKindClass = Class.forName("com.makomi.data.CrossChunkDispatchQueueSavedData$DispatchKind");
		Class<?> dispatchActionClass = Class.forName("com.makomi.data.CrossChunkDispatchQueueSavedData$DispatchAction");
		Object activationKind = java.util.Arrays
			.stream(dispatchKindClass.getEnumConstants())
			.filter(constant -> ((Enum<?>) constant).name().equals("PULSE_EVENT"))
			.findFirst()
			.orElseThrow();
		Object upsertAction = java.util.Arrays
			.stream(dispatchActionClass.getEnumConstants())
			.filter(constant -> ((Enum<?>) constant).name().equals("UPSERT"))
			.findFirst()
			.orElseThrow();
		LinkSavedData.LinkNode targetCore = new LinkSavedData.LinkNode(20L, Level.OVERWORLD, BlockPos.ZERO, LinkNodeType.CORE);

		CrossChunkDispatchService.QueueResult invalidLevel = (CrossChunkDispatchService.QueueResult) queueDispatch.invoke(
			null,
			null,
			targetCore,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			activationKind,
			upsertAction,
			ActivationMode.TOGGLE,
			0,
			0L,
			0,
			40L
		);
		assertFalse(invalidLevel.accepted());

		CrossChunkDispatchService.QueueResult invalidSourceSerial = (CrossChunkDispatchService.QueueResult) queueDispatch.invoke(
			null,
			null,
			targetCore,
			LinkNodeType.TRIGGER_SOURCE,
			0L,
			activationKind,
			upsertAction,
			ActivationMode.TOGGLE,
			0,
			0L,
			0,
			40L
		);
		assertFalse(invalidSourceSerial.accepted());
	}

	/**
	 * 私有 shouldForceLoad 在空上下文下应直接返回 false。
	 */
	@Test
	void shouldForceLoadShouldReturnFalseForNullContext() throws Exception {
		Class<?> pendingDispatchClass = Class.forName("com.makomi.data.CrossChunkDispatchQueueSavedData$PendingDispatchEntry");
		Method shouldForceLoad = CrossChunkDispatchService.class.getDeclaredMethod(
			"shouldForceLoad",
			ServerLevel.class,
			pendingDispatchClass
		);
		shouldForceLoad.setAccessible(true);
		boolean result = (boolean) shouldForceLoad.invoke(null, null, null);
		assertFalse(result);
	}

	/**
	 * force-load 窗口重置应区分“同 tick”与“新 tick”两种路径。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void resetForceLoadWindowShouldRespectTickBoundary() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> constructor = dispatchStateClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object state = constructor.newInstance();

		Field windowTickField = dispatchStateClass.getDeclaredField("forceLoadWindowTick");
		windowTickField.setAccessible(true);
		Field countField = dispatchStateClass.getDeclaredField("forceLoadCountThisTick");
		countField.setAccessible(true);
		Field bySourceField = dispatchStateClass.getDeclaredField("forceLoadCountBySource");
		bySourceField.setAccessible(true);
		Map<Object, Integer> bySource = (Map<Object, Integer>) bySourceField.get(state);

		Class<?> sourceKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$SourceKey");
		Constructor<?> sourceKeyConstructor = sourceKeyClass.getDeclaredConstructor(LinkNodeType.class, long.class);
		sourceKeyConstructor.setAccessible(true);
		bySource.put(sourceKeyConstructor.newInstance(LinkNodeType.TRIGGER_SOURCE, 1L), 1);
		windowTickField.setLong(state, 200L);
		countField.setInt(state, 5);

		Method resetMethod = CrossChunkDispatchService.class.getDeclaredMethod("resetForceLoadWindow", dispatchStateClass, long.class);
		resetMethod.setAccessible(true);
		resetMethod.invoke(null, state, 200L);
		assertEquals(5, countField.getInt(state));
		assertEquals(1, bySource.size());

		resetMethod.invoke(null, state, 201L);
		assertEquals(201L, windowTickField.getLong(state));
		assertEquals(0, countField.getInt(state));
		assertTrue(bySource.isEmpty());
	}

	/**
	 * 已持票 chunk 再次命中强制加载时，只应续期，不应重复扣除本 tick 的 force-load 预算。
	 */
	@Test
	void tryForceLoadShouldRenewExistingChunkWithoutChargingBudget(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				15L,
				LinkNodeType.CORE,
				105L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(32, 64, 32),
			ActivationMode.TOGGLE,
			15,
			80L,
			0,
			200L,
			1L
		);
		CrossChunkDispatchService.SourceKey sourceKey = new CrossChunkDispatchService.SourceKey(LinkNodeType.TRIGGER_SOURCE, 15L);
		CrossChunkDispatchService.ForcedChunkKey forcedChunkKey = new CrossChunkDispatchService.ForcedChunkKey(Level.OVERWORLD, 2, 2);
		Properties properties = new Properties();

		withCrossChunkConfig(properties, () -> {
			assertNotNull(level.getServer());
			assertNotNull(level.getServer().getLevel(Level.OVERWORLD));
			state.forceLoadWindowTick = 300L;
			state.forceLoadCountThisTick = 1;
			assertEquals(1, state.forceLoadCountThisTick);
			state.forceLoadCountBySource.put(sourceKey, 1);
			state.forcedChunksUntilTick.put(forcedChunkKey, 360L);
			long firstExpireTick = state.forcedChunksUntilTick.get(forcedChunkKey);

			CrossChunkDispatchService.tryForceLoad(level.getServer(), state, pending, 301L);
			assertEquals(1, state.forceLoadCountThisTick);
			assertEquals(1, state.forceLoadCountBySource.size());
			assertEquals(1, state.forcedChunksUntilTick.size());
			assertTrue(state.forcedChunksUntilTick.get(forcedChunkKey) > firstExpireTick);
		});
	}

	/**
	 * releaseAllForcedChunksAndClearState 在存在空状态时应完成清理并移除缓存。
	 */
	@Test
	void releaseAllForcedChunksShouldClearStateMap() throws Exception {
		Method getOrCreateState = CrossChunkDispatchService.class.getDeclaredMethod("getOrCreateState", MinecraftServer.class);
		getOrCreateState.setAccessible(true);
		Object state = getOrCreateState.invoke(null, new Object[] { null });
		assertNotNull(state);

		Method releaseAll = CrossChunkDispatchService.class.getDeclaredMethod(
			"releaseAllForcedChunksAndClearState",
			MinecraftServer.class
		);
		releaseAll.setAccessible(true);
		releaseAll.invoke(null, new Object[] { null });

		Field stateByServerField = CrossChunkDispatchService.class.getDeclaredField("STATE_BY_SERVER");
		stateByServerField.setAccessible(true);
		Map<?, ?> stateByServer = (Map<?, ?>) stateByServerField.get(null);
		assertFalse(stateByServer.containsKey(null));
	}

	/**
	 * processPendingDispatches 应移除过期请求而无需访问服务端世界。
	 */
	@Test
	void processPendingDispatchesShouldRemoveExpiredEntry() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();
		CrossChunkDispatchQueueSavedData queueData = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			5L,
			LinkNodeType.CORE,
			9L,
			CrossChunkDispatchQueueSavedData.DispatchKind.PULSE_EVENT
		);
		CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = queueData.upsertPending(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			0,
			0L,
			0,
			10L
		);
		assertTrue(upsertResult.accepted());

		Method processPendingDispatches = CrossChunkDispatchService.class.getDeclaredMethod(
			"processPendingDispatches",
			MinecraftServer.class,
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.class,
			long.class
		);
		processPendingDispatches.setAccessible(true);
		processPendingDispatches.invoke(null, null, state, queueData, 10L);

		assertTrue(queueData.pendingEntriesSnapshot().isEmpty());
	}

	/**
	 * processPendingDispatches 应按版本护栏移除已过时条目。
	 */
	@Test
	void processPendingDispatchesShouldDropStaleByAcceptedVersion() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();
		CrossChunkDispatchQueueSavedData queueData = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			7L,
			LinkNodeType.CORE,
			17L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = queueData.upsertPending(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			15,
			0L,
			0,
			200L
		);
		assertTrue(upsertResult.accepted());
		assertTrue(queueData.markAccepted(key, upsertResult.entry().version()));

		Method processPendingDispatches = CrossChunkDispatchService.class.getDeclaredMethod(
			"processPendingDispatches",
			MinecraftServer.class,
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.class,
			long.class
		);
		processPendingDispatches.setAccessible(true);
		processPendingDispatches.invoke(null, null, state, queueData, 100L);

		assertTrue(queueData.pendingEntriesSnapshot().isEmpty());
	}

	/**
	 * processPendingDispatches 应遵循每 tick 预算（默认 500）推进游标，避免单 tick 全量扫描。
	 */
	@Test
	void processPendingDispatchesShouldRespectDispatchBudget() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();

		CrossChunkDispatchQueueSavedData queueData = new CrossChunkDispatchQueueSavedData();
		for (int index = 0; index < 520; index++) {
			CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				10_000L + index,
				LinkNodeType.CORE,
				20_000L + index,
				CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
			);
			CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = queueData.upsertPending(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(index, 64, index),
				ActivationMode.TOGGLE,
				0,
				0L,
				0,
				1000L
			);
			assertTrue(upsertResult.accepted());
			assertTrue(queueData.markAccepted(key, upsertResult.entry().version()));
		}

		Method processPendingDispatches = CrossChunkDispatchService.class.getDeclaredMethod(
			"processPendingDispatches",
			MinecraftServer.class,
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.class,
			long.class
		);
		processPendingDispatches.setAccessible(true);
		processPendingDispatches.invoke(null, null, state, queueData, 100L);

		Field pendingCursorField = dispatchStateClass.getDeclaredField("pendingCursor");
		pendingCursorField.setAccessible(true);
		assertEquals(500L, pendingCursorField.getLong(state));
		assertEquals(20, queueData.pendingSize());
	}

	/**
	 * 非持久化事件达到重试上限后应被判定为可丢弃，避免无限重试。
	 */
	@Test
	void recordRetryFailureShouldDropNonPersistentEntryAtThreshold() throws Exception {
		CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();

		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			11L,
			LinkNodeType.CORE,
			22L,
			CrossChunkDispatchQueueSavedData.DispatchKind.PULSE_EVENT
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			0,
			0L,
			0,
			500L,
			1L
		);

		CrossChunkDispatchService.RetryState retryState = new CrossChunkDispatchService.RetryState();
		retryState.attempts = Math.max(0, RedstoneLinkConfig.crossChunk().retry().dropThreshold() - 1);
		CrossChunkDispatchService.PendingAttemptKey attemptKey = new CrossChunkDispatchService.PendingAttemptKey(key, 1L);
		state.retryStateByAttemptKey.put(attemptKey, retryState);

		boolean shouldDrop = CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(
			state,
			pending,
			101L
		);
		assertTrue(shouldDrop);
	}

	/**
	 * 不限时 pending 应按失败次数命中对应的分段重试窗口。
	 */
	@Test
	void shouldDeferRetryUntilEligibleShouldRespectStagedRetryWindow() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.retry.dropThreshold", "99");
		properties.setProperty("crosschunk.retry.stage1.maxAttempts", "2");
		properties.setProperty("crosschunk.retry.stage1.intervalTicks", "1");
		properties.setProperty("crosschunk.retry.stage2.maxAttempts", "4");
		properties.setProperty("crosschunk.retry.stage2.intervalTicks", "5");
		properties.setProperty("crosschunk.retry.stage3.maxAttempts", "6");
		properties.setProperty("crosschunk.retry.stage3.intervalTicks", "20");
		properties.setProperty("crosschunk.retry.stage4.intervalTicks", "100");
		withCrossChunkConfig(properties, () -> {
			CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();

			CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				33L,
				LinkNodeType.CORE,
				44L,
				CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
			);
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				BlockPos.ZERO,
				ActivationMode.TOGGLE,
				15,
				0L,
				0,
				Long.MAX_VALUE,
				2L
			);

			CrossChunkDispatchService.RetryState retryState = new CrossChunkDispatchService.RetryState();
			retryState.attempts = 4;
			retryState.nextEligibleTick = 320L;
			CrossChunkDispatchService.PendingAttemptKey attemptKey = new CrossChunkDispatchService.PendingAttemptKey(key, 2L);
			state.retryStateByAttemptKey.put(attemptKey, retryState);

			assertTrue(CrossChunkDispatchRuntimeSupport.shouldDeferRetryUntilEligible(state, pending, 305L));
			assertFalse(CrossChunkDispatchRuntimeSupport.shouldDeferRetryUntilEligible(state, pending, 320L));

			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 322L));
			assertEquals(342L, retryState.nextEligibleTick);

			retryState.attempts = 0;
			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 400L));
			assertEquals(401L, retryState.nextEligibleTick);

			retryState.attempts = 2;
			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 450L));
			assertEquals(455L, retryState.nextEligibleTick);

			retryState.attempts = 6;
			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 700L));
			assertEquals(800L, retryState.nextEligibleTick);
		});
	}

	/**
	 * 不限时 pending 应跳过通用 warn/error 标志，仅在首次进入后续分段时记录一次降频提示。
	 */
	@Test
	void recordRetryFailureShouldSkipWarnAndErrorForUnlimitedPending() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.retry.warnThreshold", "1");
		properties.setProperty("crosschunk.retry.errorThreshold", "1");
		properties.setProperty("crosschunk.retry.dropThreshold", "1");
		properties.setProperty("crosschunk.retry.stage1.maxAttempts", "1");
		properties.setProperty("crosschunk.retry.stage1.intervalTicks", "1");
		properties.setProperty("crosschunk.retry.stage2.maxAttempts", "2");
		properties.setProperty("crosschunk.retry.stage2.intervalTicks", "5");
		properties.setProperty("crosschunk.retry.stage3.maxAttempts", "3");
		properties.setProperty("crosschunk.retry.stage3.intervalTicks", "20");
		properties.setProperty("crosschunk.retry.stage4.intervalTicks", "100");
		withCrossChunkConfig(properties, () -> {
			CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();

			CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				55L,
				LinkNodeType.CORE,
				66L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			);
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				BlockPos.ZERO,
				ActivationMode.TOGGLE,
				15,
				0L,
				0,
				Long.MAX_VALUE,
				3L
			);

			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 400L));
			assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 401L));

			CrossChunkDispatchService.PendingAttemptKey attemptKey = new CrossChunkDispatchService.PendingAttemptKey(key, 3L);
			CrossChunkDispatchService.RetryState retryState = state.retryStateByAttemptKey.get(attemptKey);
			assertNotNull(retryState);

			assertFalse(retryState.warnLogged);
			assertFalse(retryState.errorLogged);
			assertTrue(retryState.stagedBackoffLogged);
			assertFalse(retryState.dropLogged);
			assertEquals(406L, retryState.nextEligibleTick);
		});
	}

	/**
	 * 目标区块加载唤醒应只解除索引中命中目标区块的等待窗口。
	 */
	@Test
	void notifyTargetChunkLoadedShouldWakeOnlyMatchingIndexedPending() throws Exception {
		CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();
		CrossChunkDispatchQueueSavedData queueData = new CrossChunkDispatchQueueSavedData();

		CrossChunkDispatchQueueSavedData.DispatchKey matchingKey = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			77L,
			LinkNodeType.CORE,
			88L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry matchingPending = queueData.upsertPending(
			matchingKey,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(34, 64, 50),
			ActivationMode.TOGGLE,
			15,
			0L,
			0,
			Long.MAX_VALUE
		).entry();
		CrossChunkDispatchQueueSavedData.DispatchKey otherKey = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			79L,
			LinkNodeType.CORE,
			90L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry otherPending = queueData.upsertPending(
			otherKey,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(80, 64, 80),
			ActivationMode.TOGGLE,
			15,
			0L,
			0,
			Long.MAX_VALUE
		).entry();

		assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, matchingPending, 100L));
		assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, otherPending, 100L));

		CrossChunkDispatchService.PendingAttemptKey matchingAttemptKey =
			new CrossChunkDispatchService.PendingAttemptKey(matchingKey, matchingPending.version());
		CrossChunkDispatchService.PendingAttemptKey otherAttemptKey =
			new CrossChunkDispatchService.PendingAttemptKey(otherKey, otherPending.version());

		CrossChunkDispatchService.RetryState matchingRetryState = state.retryStateByAttemptKey.get(matchingAttemptKey);
		CrossChunkDispatchService.RetryState otherRetryState = state.retryStateByAttemptKey.get(otherAttemptKey);
		assertNotNull(matchingRetryState);
		assertNotNull(otherRetryState);
		matchingRetryState.nextEligibleTick = 500L;
		otherRetryState.nextEligibleTick = 600L;

		Map<CrossChunkDispatchService.TargetChunkKey, Set<CrossChunkDispatchService.PendingAttemptKey>> waitingIndex =
			state.waitingUnlimitedAttemptKeysByTargetChunk;
		assertEquals(2, waitingIndex.values().stream().mapToInt(Set::size).sum());

		int awakened = CrossChunkDispatchRuntimeSupport.notifyTargetChunkLoaded(
			state,
			queueData,
			Level.OVERWORLD,
			new ChunkPos(2, 3),
			120L
		);

		assertEquals(1, awakened);
		assertEquals(120L, matchingRetryState.nextEligibleTick);
		assertEquals(600L, otherRetryState.nextEligibleTick);
		assertEquals(1, waitingIndex.values().stream().mapToInt(Set::size).sum());
	}

	/**
	 * clearRetryState 应同步移除目标区块唤醒索引，避免残留等待桶。
	 */
	@Test
	void clearRetryStateShouldRemoveWakeIndexEntry() throws Exception {
		CrossChunkDispatchService.DispatchState state = new CrossChunkDispatchService.DispatchState();
		CrossChunkDispatchQueueSavedData queueData = new CrossChunkDispatchQueueSavedData();

		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			91L,
			LinkNodeType.CORE,
			92L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending = queueData.upsertPending(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(16, 70, 16),
			ActivationMode.TOGGLE,
			15,
			0L,
			0,
			Long.MAX_VALUE
		).entry();

		assertFalse(CrossChunkDispatchRuntimeSupport.recordRetryFailureAndShouldDrop(state, pending, 200L));

		Map<CrossChunkDispatchService.TargetChunkKey, Set<CrossChunkDispatchService.PendingAttemptKey>> waitingIndex =
			state.waitingUnlimitedAttemptKeysByTargetChunk;
		assertEquals(1, waitingIndex.values().stream().mapToInt(Set::size).sum());

		CrossChunkDispatchRuntimeSupport.clearRetryState(state, pending);

		assertTrue(state.retryStateByAttemptKey.isEmpty());
		assertTrue(waitingIndex.isEmpty());
	}

	/**
	 * TargetLocatorCache 应按同一 target 键复用定位结果，避免同 tick 重复建桶。
	 */
	@Test
	void targetLocatorCacheShouldReuseSameTargetKey() {
		CrossChunkDispatchRuntimeSupport.TargetLocatorCache cache = new CrossChunkDispatchRuntimeSupport.TargetLocatorCache();
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry firstPending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				201L,
				LinkNodeType.CORE,
				301L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(32, 64, 32),
			ActivationMode.TOGGLE,
			15,
			50L,
			0,
			100L,
			1L
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry sameTargetPending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				202L,
				LinkNodeType.CORE,
				301L,
				CrossChunkDispatchQueueSavedData.DispatchKind.TRIGGER_SOURCE_INVALIDATION
			),
			CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE,
			Level.OVERWORLD,
			new BlockPos(32, 64, 32),
			ActivationMode.TOGGLE,
			0,
			50L,
			1,
			100L,
			2L
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry otherTargetPending = new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				203L,
				LinkNodeType.CORE,
				302L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			new BlockPos(48, 64, 48),
			ActivationMode.TOGGLE,
			7,
			50L,
			2,
			100L,
			3L
		);

		assertEquals(
			CrossChunkDispatchRuntimeSupport.TargetLocatorStatus.RETRYABLE_MISS,
			cache.locate(null, firstPending).status()
		);
		assertEquals(
			CrossChunkDispatchRuntimeSupport.TargetLocatorStatus.RETRYABLE_MISS,
			cache.locate(null, sameTargetPending).status()
		);
		assertEquals(1, cache.size());

		cache.locate(null, otherTargetPending);
		assertEquals(2, cache.size());
	}

	/**
	 * ChunkReadyDrainCache 应先按 chunk，再按 core 分组 ready 的 batchable 条目。
	 */
	@Test
	void chunkReadyDrainCacheShouldGroupBatchableEntriesByChunkAndTarget() {
		CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache drainCache = new CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache();
		TestTargetEntity firstTarget = createTarget();
		TestTargetEntity secondTarget = createTarget();
		TestTargetEntity thirdTarget = createTarget();

		CrossChunkDispatchRuntimeSupport.PreparedDispatch firstDispatch = CrossChunkDispatchRuntimeSupport.prepareDispatch(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					401L,
					LinkNodeType.CORE,
					501L,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				ActivationMode.TOGGLE,
				15,
				60L,
				0,
				200L,
				11L
			),
			firstTarget
		);
		CrossChunkDispatchRuntimeSupport.PreparedDispatch secondDispatch = CrossChunkDispatchRuntimeSupport.prepareDispatch(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					402L,
					LinkNodeType.CORE,
					501L,
					CrossChunkDispatchQueueSavedData.DispatchKind.TRIGGER_SOURCE_INVALIDATION
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE,
				Level.OVERWORLD,
				new BlockPos(32, 64, 32),
				ActivationMode.TOGGLE,
				0,
				60L,
				1,
				200L,
				12L
			),
			firstTarget
		);
		CrossChunkDispatchRuntimeSupport.PreparedDispatch thirdDispatch = CrossChunkDispatchRuntimeSupport.prepareDispatch(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					403L,
					LinkNodeType.CORE,
					502L,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(40, 64, 40),
				ActivationMode.TOGGLE,
				9,
				60L,
				2,
				200L,
				13L
			),
			secondTarget
		);
		CrossChunkDispatchRuntimeSupport.PreparedDispatch fourthDispatch = CrossChunkDispatchRuntimeSupport.prepareDispatch(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					404L,
					LinkNodeType.CORE,
					503L,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(64, 64, 64),
				ActivationMode.TOGGLE,
				6,
				60L,
				3,
				200L,
				14L
			),
			thirdTarget
		);

		drainCache.stageBatchable(firstDispatch);
		drainCache.stageBatchable(secondDispatch);
		drainCache.stageBatchable(thirdDispatch);
		drainCache.stageBatchable(fourthDispatch);

		assertEquals(2, drainCache.chunkBucketCount());
		assertEquals(3, drainCache.targetGroupCount());
	}

	/**
	 * ChunkReadyDrainCache 的 retained pool 应限制在常态复用上限内。
	 */
	@Test
	void chunkReadyDrainCacheShouldCapRetainedPools() throws Exception {
		CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache drainCache = new CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache();
		Field mapCapField = CrossChunkDispatchRuntimeSupport.class.getDeclaredField("MAX_RETAINED_READY_GROUP_MAPS");
		mapCapField.setAccessible(true);
		int mapCap = mapCapField.getInt(null);
		Field groupCapField = CrossChunkDispatchRuntimeSupport.class.getDeclaredField("MAX_RETAINED_READY_GROUPS");
		groupCapField.setAccessible(true);
		int groupCap = groupCapField.getInt(null);
		int dispatchCount = Math.max(mapCap, groupCap) + 5;

		for (int index = 0; index < dispatchCount; index++) {
			drainCache.stageBatchable(
				createPreparedDispatch(600L + index, 900L + index, new BlockPos(index * 16, 64, index * 16), 50L + index)
			);
		}
		drainCache.reset();

		Field readyGroupMapPoolField = CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache.class.getDeclaredField(
			"readyGroupMapPool"
		);
		readyGroupMapPoolField.setAccessible(true);
		Field readyGroupPoolField = CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache.class.getDeclaredField(
			"readyGroupPool"
		);
		readyGroupPoolField.setAccessible(true);

		assertEquals(mapCap, ((List<?>) readyGroupMapPoolField.get(drainCache)).size());
		assertEquals(groupCap, ((List<?>) readyGroupPoolField.get(drainCache)).size());
	}

	/**
	 * 当 pending 队列已空时，应释放 ready drain retained pool，避免继续挂在 dispatch state 上。
	 */
	@Test
	void processPendingDispatchesShouldClearReadyDrainRetainedPoolsWhenQueueIsEmpty() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();

		Field readyDrainCacheField = dispatchStateClass.getDeclaredField("readyDrainCache");
		readyDrainCacheField.setAccessible(true);
		CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache drainCache =
			(CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache) readyDrainCacheField.get(state);
		drainCache.stageBatchable(createPreparedDispatch(700L, 800L, new BlockPos(32, 64, 32), 88L));
		drainCache.reset();

		Field readyGroupMapPoolField = CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache.class.getDeclaredField(
			"readyGroupMapPool"
		);
		readyGroupMapPoolField.setAccessible(true);
		Field readyGroupPoolField = CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache.class.getDeclaredField(
			"readyGroupPool"
		);
		readyGroupPoolField.setAccessible(true);
		assertEquals(1, ((List<?>) readyGroupMapPoolField.get(drainCache)).size());
		assertEquals(1, ((List<?>) readyGroupPoolField.get(drainCache)).size());

		Method processPendingDispatches = CrossChunkDispatchService.class.getDeclaredMethod(
			"processPendingDispatches",
			MinecraftServer.class,
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.class,
			long.class
		);
		processPendingDispatches.setAccessible(true);
		processPendingDispatches.invoke(null, null, state, new CrossChunkDispatchQueueSavedData(), 0L);

		assertEquals(0, ((List<?>) readyGroupMapPoolField.get(drainCache)).size());
		assertEquals(0, ((List<?>) readyGroupPoolField.get(drainCache)).size());
	}

	/**
	 * appendDesiredResidentTickets 应按 role/type 约束构造常驻票据目标。
	 */
	@Test
	void appendDesiredResidentTicketsShouldBuildTicketBySemanticRole() throws Exception {
		LinkSavedData linkSavedData = new LinkSavedData();
		linkSavedData.registerNode(101L, Level.OVERWORLD, new BlockPos(33, 64, 49), LinkNodeType.TRIGGER_SOURCE);

		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired =
			new java.util.HashMap<>();
		CrossChunkDispatchTicketSupport.appendDesiredResidentTickets(
			desired,
			Map.of(LinkNodeType.TRIGGER_SOURCE, Set.of(101L)),
			LinkNodeSemantics.Role.SOURCE,
			linkSavedData
		);
		assertEquals(1, desired.size());
		Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> entry =
			desired.entrySet().iterator().next();
		assertEquals(LinkNodeSemantics.Role.SOURCE, entry.getKey().role());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, entry.getKey().type());
		assertEquals(101L, entry.getKey().serial());
		assertEquals(Level.OVERWORLD, entry.getValue().dimension());
		assertEquals(2, entry.getValue().chunkX());
		assertEquals(3, entry.getValue().chunkZ());

		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> roleFiltered =
			new java.util.HashMap<>();
		CrossChunkDispatchTicketSupport.appendDesiredResidentTickets(
			roleFiltered,
			Map.of(LinkNodeType.TRIGGER_SOURCE, Set.of(101L)),
			LinkNodeSemantics.Role.TARGET,
			linkSavedData
		);
		assertTrue(roleFiltered.isEmpty());
	}

	/**
	 * resident 版本门禁应仅在 resident 集合真值变化时递增。
	 */
	@Test
	void crossChunkWhitelistSavedDataResidentStateVersionShouldTrackResidentMutationOnly() {
		CrossChunkWhitelistSavedData whitelistSavedData = new CrossChunkWhitelistSavedData();
		assertEquals(0L, whitelistSavedData.residentStateVersion());
		assertFalse(whitelistSavedData.hasResidents());

		CrossChunkWhitelistSavedData.UpsertResult nonResidentUpsert = whitelistSavedData.upsert(
			LinkNodeType.TRIGGER_SOURCE,
			151L,
			LinkNodeSemantics.Role.SOURCE,
			false
		);
		assertTrue(nonResidentUpsert.created());
		assertFalse(nonResidentUpsert.residentChanged());
		assertEquals(0L, whitelistSavedData.residentStateVersion());
		assertFalse(whitelistSavedData.hasResidents());

		CrossChunkWhitelistSavedData.UpsertResult residentUpsert = whitelistSavedData.upsert(
			LinkNodeType.TRIGGER_SOURCE,
			151L,
			LinkNodeSemantics.Role.SOURCE,
			true
		);
		assertTrue(residentUpsert.residentChanged());
		assertEquals(1L, whitelistSavedData.residentStateVersion());
		assertTrue(whitelistSavedData.hasResidents());

		CrossChunkWhitelistSavedData.UpsertResult residentNoop = whitelistSavedData.upsert(
			LinkNodeType.TRIGGER_SOURCE,
			151L,
			LinkNodeSemantics.Role.SOURCE,
			true
		);
		assertFalse(residentNoop.changed());
		assertEquals(1L, whitelistSavedData.residentStateVersion());

		assertTrue(whitelistSavedData.remove(LinkNodeType.TRIGGER_SOURCE, 151L, LinkNodeSemantics.Role.SOURCE));
		assertEquals(2L, whitelistSavedData.residentStateVersion());
		assertFalse(whitelistSavedData.hasResidents());
	}

	/**
	 * 运行态节点版本应只在在线节点拓扑变化时递增。
	 */
	@Test
	void linkSavedDataRuntimeNodeVersionShouldTrackOnlineTopologyOnly() {
		LinkSavedData linkSavedData = new LinkSavedData();
		assertEquals(0L, linkSavedData.runtimeNodeVersion());

		linkSavedData.registerNode(261L, Level.OVERWORLD, new BlockPos(16, 64, 16), LinkNodeType.TRIGGER_SOURCE);
		assertEquals(1L, linkSavedData.runtimeNodeVersion());

		linkSavedData.registerNode(261L, Level.OVERWORLD, new BlockPos(16, 64, 16), LinkNodeType.TRIGGER_SOURCE);
		assertEquals(1L, linkSavedData.runtimeNodeVersion());

		linkSavedData.registerNode(261L, Level.OVERWORLD, new BlockPos(32, 64, 32), LinkNodeType.TRIGGER_SOURCE);
		assertEquals(2L, linkSavedData.runtimeNodeVersion());

		linkSavedData.removeNode(LinkNodeType.TRIGGER_SOURCE, 261L);
		assertEquals(3L, linkSavedData.runtimeNodeVersion());

		linkSavedData.removeNode(LinkNodeType.TRIGGER_SOURCE, 261L);
		assertEquals(3L, linkSavedData.runtimeNodeVersion());
	}

	/**
	 * resident 票据键应可稳定构造并暴露记录字段。
	 */
	@Test
	void residentTicketRecordsShouldExposeFields() throws Exception {
		Class<?> residentTicketKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ResidentTicketKey");
		Constructor<?> residentTicketKeyConstructor = residentTicketKeyClass.getDeclaredConstructor(
			LinkNodeSemantics.Role.class,
			LinkNodeType.class,
			long.class
		);
		residentTicketKeyConstructor.setAccessible(true);
		Object ticketKey = residentTicketKeyConstructor.newInstance(
			LinkNodeSemantics.Role.SOURCE,
			LinkNodeType.TRIGGER_SOURCE,
			101L
		);
		assertEquals(LinkNodeSemantics.Role.SOURCE, residentTicketKeyClass.getDeclaredMethod("role").invoke(ticketKey));
		assertEquals(LinkNodeType.TRIGGER_SOURCE, residentTicketKeyClass.getDeclaredMethod("type").invoke(ticketKey));
		assertEquals(101L, residentTicketKeyClass.getDeclaredMethod("serial").invoke(ticketKey));

		Class<?> residentChunkKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ResidentChunkKey");
		Constructor<?> residentChunkKeyConstructor = residentChunkKeyClass.getDeclaredConstructor(
			net.minecraft.resources.ResourceKey.class,
			int.class,
			int.class
		);
		residentChunkKeyConstructor.setAccessible(true);
		Object chunkKey = residentChunkKeyConstructor.newInstance(Level.OVERWORLD, 3, 7);
		assertEquals(Level.OVERWORLD, residentChunkKeyClass.getDeclaredMethod("dimension").invoke(chunkKey));
		assertEquals(3, residentChunkKeyClass.getDeclaredMethod("chunkX").invoke(chunkKey));
		assertEquals(7, residentChunkKeyClass.getDeclaredMethod("chunkZ").invoke(chunkKey));
	}

	private static void withCrossChunkConfig(Properties properties, ThrowingRunnable action) throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, action::run);
	}

	/**
	 * 构造强制加载计费测试所需的最小 ServerLevel。
	 */
	private static ServerLevel createServerLevel(Path tempDir) throws Exception {
		Unsafe unsafe = unsafe();
		ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
		DedicatedServer server = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
		ServerChunkCache chunkCache = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
		SavedDataStorage dataStorage = TestMinecraftSupport.createSavedDataStorage(tempDir);
		Object levelDataProxy = createLevelDataProxy();

		setField(Level.class, level, "isClientSide", false);
		setField(Level.class, level, "dimension", Level.OVERWORLD);
		setField(Level.class, level, "levelData", levelDataProxy);
		setField(ServerLevel.class, level, "server", server);
		setField(ServerLevel.class, level, "serverLevelData", levelDataProxy);
		setField(ServerLevel.class, level, "chunkSource", chunkCache);
		setField(ServerChunkCache.class, chunkCache, "level", level);
		setField(ServerChunkCache.class, chunkCache, "dataStorage", dataStorage);
		setField(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, level));
		return level;
	}

	/**
	 * 只实现当前计费测试会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			CrossChunkDispatchServiceTest.class.getClassLoader(),
			new Class<?>[] { levelDataType, serverLevelDataType },
			(proxy, method, args) -> switch (method.getName()) {
				case "getGameTime", "getDayTime" -> 0L;
				case "isHardcore", "isFlatWorld", "isRaining", "isThundering" -> false;
				default -> defaultValue(method.getReturnType());
			}
		);
	}

	/**
	 * 通过反射写入最小测试夹具字段。
	 */
	private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
		if (owner == ServerChunkCache.class && ("dataStorage".equals(fieldName) || "savedDataStorage".equals(fieldName))) {
			TestMinecraftSupport.setFieldByCandidates(owner, target, value, "savedDataStorage", "dataStorage");
			return;
		}
		TestMinecraftSupport.setFieldByCandidates(owner, target, value, fieldName);
	}

	private static Object defaultValue(Class<?> returnType) {
		if (returnType == null || !returnType.isPrimitive()) {
			return null;
		}
		if (returnType == boolean.class) {
			return false;
		}
		if (returnType == long.class) {
			return 0L;
		}
		if (returnType == int.class) {
			return 0;
		}
		if (returnType == short.class) {
			return (short) 0;
		}
		if (returnType == byte.class) {
			return (byte) 0;
		}
		if (returnType == float.class) {
			return 0.0F;
		}
		if (returnType == double.class) {
			return 0.0D;
		}
		if (returnType == char.class) {
			return '\0';
		}
		return null;
	}

	private static Unsafe unsafe() throws Exception {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>) type;
	}

	private static TestTargetEntity createTarget() {
		return new TestTargetEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
	}

	private static CrossChunkDispatchRuntimeSupport.PreparedDispatch createPreparedDispatch(
		long sourceSerial,
		long targetSerial,
		BlockPos pos,
		long version
	) {
		return CrossChunkDispatchRuntimeSupport.prepareDispatch(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					sourceSerial,
					LinkNodeType.CORE,
					targetSerial,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				pos,
				ActivationMode.TOGGLE,
				15,
				60L,
				0,
				200L,
				version
			),
			createTarget()
		);
	}

	/**
	 * 供 runtime 分组测试使用的最小 `core` 实体。
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

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
