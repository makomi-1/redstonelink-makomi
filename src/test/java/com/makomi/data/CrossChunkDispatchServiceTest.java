package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkDispatchService 参数守卫与状态机测试。
 */
@Tag("stable-core")
class CrossChunkDispatchServiceTest {
	/**
	 * register 入口应可重复调用（仅注册回调，不抛异常）。
	 */
	@Test
	void registerShouldBeCallable() {
		CrossChunkDispatchService.register();
		CrossChunkDispatchService.register();
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
			.filter(constant -> ((Enum<?>) constant).name().equals("ACTIVATION"))
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
			CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
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
				CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
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
	@SuppressWarnings("unchecked")
	void recordRetryFailureShouldDropNonPersistentEntryAtThreshold() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();

		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			11L,
			LinkNodeType.CORE,
			22L,
			CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
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

		Class<?> retryStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$RetryState");
		Constructor<?> retryStateConstructor = retryStateClass.getDeclaredConstructor();
		retryStateConstructor.setAccessible(true);
		Object retryState = retryStateConstructor.newInstance();
		Field attemptsField = retryStateClass.getDeclaredField("attempts");
		attemptsField.setAccessible(true);
		attemptsField.setInt(retryState, Math.max(0, RedstoneLinkConfig.crossChunkRetryDropThreshold() - 1));
		Field lastAttemptTickField = retryStateClass.getDeclaredField("lastAttemptTick");
		lastAttemptTickField.setAccessible(true);
		lastAttemptTickField.setLong(retryState, 100L);

		Class<?> pendingAttemptKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$PendingAttemptKey");
		Constructor<?> pendingAttemptKeyConstructor = pendingAttemptKeyClass.getDeclaredConstructor(
			CrossChunkDispatchQueueSavedData.DispatchKey.class,
			long.class
		);
		pendingAttemptKeyConstructor.setAccessible(true);
		Object attemptKey = pendingAttemptKeyConstructor.newInstance(key, 1L);

		Field retryMapField = dispatchStateClass.getDeclaredField("retryStateByAttemptKey");
		retryMapField.setAccessible(true);
		Map<Object, Object> retryMap = (Map<Object, Object>) retryMapField.get(state);
		retryMap.put(attemptKey, retryState);

		Method onFailure = CrossChunkDispatchService.class.getDeclaredMethod(
			"recordRetryFailureAndShouldDrop",
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry.class,
			long.class
		);
		onFailure.setAccessible(true);
		boolean shouldDrop = (boolean) onFailure.invoke(null, state, pending, 101L);
		assertTrue(shouldDrop);
	}

	/**
	 * 持久化 SYNC 达到重试上限后应进入退避窗口而非直接丢弃。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void shouldBackoffRetryShouldThrottlePersistentSyncAtThreshold() throws Exception {
		Class<?> dispatchStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchState");
		Constructor<?> stateConstructor = dispatchStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object state = stateConstructor.newInstance();

		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			33L,
			LinkNodeType.CORE,
			44L,
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
			2L
		);

		Class<?> retryStateClass = Class.forName("com.makomi.data.CrossChunkDispatchService$RetryState");
		Constructor<?> retryStateConstructor = retryStateClass.getDeclaredConstructor();
		retryStateConstructor.setAccessible(true);
		Object retryState = retryStateConstructor.newInstance();
		Field attemptsField = retryStateClass.getDeclaredField("attempts");
		attemptsField.setAccessible(true);
		attemptsField.setInt(retryState, Math.max(1, RedstoneLinkConfig.crossChunkRetryDropThreshold()));
		Field lastAttemptTickField = retryStateClass.getDeclaredField("lastAttemptTick");
		lastAttemptTickField.setAccessible(true);
		lastAttemptTickField.setLong(retryState, 300L);

		Class<?> pendingAttemptKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$PendingAttemptKey");
		Constructor<?> pendingAttemptKeyConstructor = pendingAttemptKeyClass.getDeclaredConstructor(
			CrossChunkDispatchQueueSavedData.DispatchKey.class,
			long.class
		);
		pendingAttemptKeyConstructor.setAccessible(true);
		Object attemptKey = pendingAttemptKeyConstructor.newInstance(key, 2L);

		Field retryMapField = dispatchStateClass.getDeclaredField("retryStateByAttemptKey");
		retryMapField.setAccessible(true);
		Map<Object, Object> retryMap = (Map<Object, Object>) retryMapField.get(state);
		retryMap.put(attemptKey, retryState);

		Method shouldBackoff = CrossChunkDispatchService.class.getDeclaredMethod(
			"shouldBackoffRetry",
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry.class,
			long.class
		);
		shouldBackoff.setAccessible(true);
		assertTrue((boolean) shouldBackoff.invoke(null, state, pending, 305L));
		assertFalse((boolean) shouldBackoff.invoke(null, state, pending, 325L));

		Method onFailure = CrossChunkDispatchService.class.getDeclaredMethod(
			"recordRetryFailureAndShouldDrop",
			dispatchStateClass,
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry.class,
			long.class
		);
		onFailure.setAccessible(true);
		assertFalse((boolean) onFailure.invoke(null, state, pending, 326L));
	}

	/**
	 * appendDesiredResidentTickets 应按 role/type 约束构造常驻票据目标。
	 */
	@Test
	void appendDesiredResidentTicketsShouldBuildTicketBySemanticRole() throws Exception {
		LinkSavedData linkSavedData = new LinkSavedData();
		linkSavedData.registerNode(101L, Level.OVERWORLD, new BlockPos(33, 64, 49), LinkNodeType.TRIGGER_SOURCE);

		Class<?> residentTicketKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ResidentTicketKey");
		Class<?> residentChunkKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ResidentChunkKey");
		Method appendDesiredResidentTickets = CrossChunkDispatchService.class.getDeclaredMethod(
			"appendDesiredResidentTickets",
			Map.class,
			Map.class,
			LinkNodeSemantics.Role.class,
			LinkSavedData.class
		);
		appendDesiredResidentTickets.setAccessible(true);

		Map<Object, Object> desired = new java.util.HashMap<>();
		appendDesiredResidentTickets.invoke(
			null,
			desired,
			Map.of(LinkNodeType.TRIGGER_SOURCE, Set.of(101L)),
			LinkNodeSemantics.Role.SOURCE,
			linkSavedData
		);
		assertEquals(1, desired.size());
		Map.Entry<Object, Object> entry = desired.entrySet().iterator().next();
		assertEquals(LinkNodeSemantics.Role.SOURCE, residentTicketKeyClass.getDeclaredMethod("role").invoke(entry.getKey()));
		assertEquals(LinkNodeType.TRIGGER_SOURCE, residentTicketKeyClass.getDeclaredMethod("type").invoke(entry.getKey()));
		assertEquals(101L, residentTicketKeyClass.getDeclaredMethod("serial").invoke(entry.getKey()));
		assertEquals(Level.OVERWORLD, residentChunkKeyClass.getDeclaredMethod("dimension").invoke(entry.getValue()));
		assertEquals(2, residentChunkKeyClass.getDeclaredMethod("chunkX").invoke(entry.getValue()));
		assertEquals(3, residentChunkKeyClass.getDeclaredMethod("chunkZ").invoke(entry.getValue()));

		Map<Object, Object> roleFiltered = new java.util.HashMap<>();
		appendDesiredResidentTickets.invoke(
			null,
			roleFiltered,
			Map.of(LinkNodeType.TRIGGER_SOURCE, Set.of(101L)),
			LinkNodeSemantics.Role.TARGET,
			linkSavedData
		);
		assertTrue(roleFiltered.isEmpty());
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
}
