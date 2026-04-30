package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.config.RedstoneLinkConfigTestHelper;
import com.makomi.testsupport.TestMinecraftSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
	 * 旧的 hard invalidation 配置键即使写成 false，也不应让普通来源失效事件静默。
	 */
	@Test
	void publishLinkDetachedShouldIgnoreLegacyHardInvalidationProperty() throws Exception {
		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceHardInvalidation.enabled", "false");
		withCrossChunkConfig(properties, () ->
			InternalDispatchDeltaEvents.publishLinkDetached(
				dummyServerLevel(),
				LinkNodeType.TRIGGER_SOURCE,
				11L,
				Set.of(101L),
				EventMeta.of(401L, 0, 8L)
			)
		);

		assertEquals(ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION, published.get().deltaKind());
		assertEquals(11L, published.get().sourceSerial());
		assertEquals(101L, published.get().targetSerial());
	}

	/**
	 * `sync` 专用失效事件应发布 `SOURCE_INVALIDATION`，避免误伤 toggle/pulse。
	 */
	@Test
	void publishSourceInvalidationShouldEmitSyncOnlyInvalidation() {
		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		InternalDispatchDeltaEvents.publishSourceInvalidation(
			dummyServerLevel(),
			LinkNodeType.TRIGGER_SOURCE,
			21L,
			Set.of(121L),
			EventMeta.of(450L, 0, 3L)
		);

		assertEquals(ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION, published.get().deltaKind());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, published.get().sourceType());
		assertEquals(21L, published.get().sourceSerial());
		assertEquals(LinkNodeType.CORE, published.get().targetType());
		assertEquals(121L, published.get().targetSerial());
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
		properties.setProperty("crosschunk.triggerSourceContextDetachInvalidation.enabled", "true");
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

	/**
	 * 当前真值不可读时，应回退到已持久化 replay 快照补发一次 sync。
	 */
	@Test
	void publishTriggerSourceCurrentOrPersistedSyncReplayShouldFallbackToPersistedSnapshot(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.registerNode(15L, Level.OVERWORLD, new BlockPos(1, 64, 1), LinkNodeType.TRIGGER_SOURCE);
		savedData.registerNode(105L, Level.OVERWORLD, new BlockPos(33, 64, 33), LinkNodeType.CORE);
		savedData.putTriggerSourceReplaySyncSnapshot(15L, EventMeta.of(711L, 0, 35L), 6);

		AtomicReference<InternalDispatchDeltaEvents.DispatchDeltaEvent> published = new AtomicReference<>();
		InternalDispatchDeltaEvents.register(published::set);

		InternalDispatchDeltaRuleSupport.publishTriggerSourceCurrentOrPersistedSyncReplay(
			level,
			15L,
			Set.of(105L),
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE
		);

		assertEquals(ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL, published.get().deltaKind());
		assertEquals(15L, published.get().sourceSerial());
		assertEquals(105L, published.get().targetSerial());
		assertEquals(EventMeta.of(711L, 0, 35L), published.get().eventMeta());
		assertEquals(6, published.get().syncSignalStrength());
	}

	/**
	 * 新增边 attach replay 遇到 send 过滤器拦截时，应直接拒绝补发。
	 */
	@Test
	void allowsLinkAttachedReplayByPersistedFiltersShouldRejectWhenSendFilterBlocksTriggerSource(@TempDir Path tempDir)
		throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.registerNode(11L, Level.OVERWORLD, new BlockPos(1, 64, 1), LinkNodeType.TRIGGER_SOURCE);
		savedData.registerNode(21L, Level.OVERWORLD, new BlockPos(31, 64, 31), LinkNodeType.CORE);
		assertTrue(
			PlacedLinkFilterSavedData
				.get(level)
				.upsert(
					LinkFilterKind.SEND,
					Level.OVERWORLD,
					new BlockPos(0, 64, 0),
					nodeSetOnlyConfig("11", LinkFilterNodeSetMode.BLOCKLIST),
					15
				)
		);

		assertFalse(InternalDispatchDeltaRuleSupport.allowsLinkAttachedReplayByPersistedFilters(level, 11L, 21L, 9));
	}

	/**
	 * 新增边 attach replay 遇到 receive 过滤器拦截时，应直接拒绝补发。
	 */
	@Test
	void allowsLinkAttachedReplayByPersistedFiltersShouldRejectWhenReceiveFilterBlocksCore(@TempDir Path tempDir)
		throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.registerNode(11L, Level.OVERWORLD, new BlockPos(1, 64, 1), LinkNodeType.TRIGGER_SOURCE);
		savedData.registerNode(21L, Level.OVERWORLD, new BlockPos(31, 64, 31), LinkNodeType.CORE);
		assertTrue(
			PlacedLinkFilterSavedData
				.get(level)
				.upsert(
					LinkFilterKind.RECEIVE,
					Level.OVERWORLD,
					new BlockPos(32, 64, 32),
					nodeSetOnlyConfig("21", LinkFilterNodeSetMode.BLOCKLIST),
					15
				)
		);

		assertFalse(InternalDispatchDeltaRuleSupport.allowsLinkAttachedReplayByPersistedFilters(level, 11L, 21L, 9));
	}

	/**
	 * 新增边 attach replay 在双侧过滤均放行时，应允许继续补发。
	 */
	@Test
	void allowsLinkAttachedReplayByPersistedFiltersShouldAllowWhenSendAndReceiveFiltersPass(@TempDir Path tempDir)
		throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.registerNode(11L, Level.OVERWORLD, new BlockPos(2, 64, 2), LinkNodeType.TRIGGER_SOURCE);
		savedData.registerNode(21L, Level.OVERWORLD, new BlockPos(30, 64, 30), LinkNodeType.CORE);
		assertTrue(
			PlacedLinkFilterSavedData
				.get(level)
				.upsert(
					LinkFilterKind.SEND,
					Level.OVERWORLD,
					new BlockPos(0, 64, 0),
					nodeSetOnlyConfig("11", LinkFilterNodeSetMode.WHITELIST),
					15
				)
		);
		assertTrue(
			PlacedLinkFilterSavedData
				.get(level)
				.upsert(
					LinkFilterKind.RECEIVE,
					Level.OVERWORLD,
					new BlockPos(32, 64, 32),
					nodeSetOnlyConfig("21", LinkFilterNodeSetMode.WHITELIST),
					15
				)
		);

		assertTrue(InternalDispatchDeltaRuleSupport.allowsLinkAttachedReplayByPersistedFilters(level, 11L, 21L, 9));
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

	/**
	 * 构造 attach replay 过滤测试所需的最小 ServerLevel。
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
	 * 只实现本轮过滤测试会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			InternalDispatchDeltaEventsTest.class.getClassLoader(),
			new Class<?>[] { levelDataType, serverLevelDataType },
			(proxy, method, args) -> switch (method.getName()) {
				case "getGameTime", "getDayTime" -> 0L;
				case "isHardcore", "isFlatWorld", "isRaining", "isThundering" -> false;
				default -> defaultValue(method.getReturnType());
			}
		);
	}

	private static void withCrossChunkConfig(Properties properties, ThrowingRunnable action) throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, action::run);
	}

	/**
	 * 通过反射写入最小测试夹具字段。
	 */
	private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
		Field field = owner.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static ServerLevel dummyServerLevel() {
		try {
			return (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to allocate dummy ServerLevel", ex);
		}
	}

	/**
	 * 与 LinkDispatchFilterServiceTest 保持一致的“仅节点集合”过滤配置。
	 */
	private static LinkFilterConfigSnapshot nodeSetOnlyConfig(String serialExpression, LinkFilterNodeSetMode nodeSetMode) {
		return new LinkFilterConfigSnapshot(
			serialExpression,
			nodeSetMode,
			LinkFilterSignalThresholdSource.FIXED_INPUT,
			15,
			LinkFilterSignalMode.DISABLED
		);
	}

	/**
	 * 读取 Unsafe，避免重复内联反射样板。
	 */
	private static Unsafe unsafe() throws ReflectiveOperationException {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	/**
	 * 生成代理默认返回值，避免无关访问抛出类型错误。
	 */
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

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
