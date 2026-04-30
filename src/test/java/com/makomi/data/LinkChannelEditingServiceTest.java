package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.data.LinkSavedDataChannelSupport.ChannelOverride;
import com.makomi.testsupport.TestMinecraftSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/**
 * 频道编辑服务黑盒回归测试。
 */
@Tag("stable-core")
class LinkChannelEditingServiceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * triggerSource 切到频道模式后，应把普通边替换为该频道当前 core 集合。
	 */
	@Test
	void triggerSourceChannelUpdateShouldExpandChannelMembersIntoNormalLinks(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 299L);
		savedData.addTriggerSourceCoreLink(101L, 299L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 201L, 7L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 202L, 7L);

		LinkChannelEditingService.PreparationResult preparation = LinkChannelEditingService.prepareConfirmedSetChannel(
			level,
			null,
			LinkNodeType.TRIGGER_SOURCE,
			101L,
			7L,
			false,
			false
		);

		assertTrue(preparation.successful());
		assertTrue(preparation.plan().hasChanges());
		assertEquals(1, preparation.plan().changedTriggerSourceCount());
		assertEquals(Set.of(201L, 202L), preparation.plan().preparedOperations().get(0).targets());

		LinkChannelEditingService.ApplyResult applyResult = LinkChannelEditingService.applyPreparedSetChannel(preparation.plan());
		assertEquals(1, applyResult.appliedOperationCount());
		assertEquals(2, applyResult.currentLinkedPeerCount());
		assertEquals(LinkConnectionMode.CHANNEL, savedData.getConnectionMode(LinkNodeType.TRIGGER_SOURCE, 101L));
		assertEquals(7L, savedData.getChannel(LinkNodeType.TRIGGER_SOURCE, 101L));
		assertEquals(Set.of(201L, 202L), savedData.getLinkedCoresByTriggerSource(101L));
		assertFalse(savedData.getLinkedCoresByTriggerSource(101L).contains(299L));
	}

	/**
	 * core 切到频道模式后，应同时更新“频道来源”与“仍停留在 serial 的旧来源”两侧普通边。
	 */
	@Test
	void coreChannelUpdateShouldRetargetAffectedTriggerSourcesByMode(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 401L);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 402L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 501L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 502L);
		savedData.addTriggerSourceCoreLink(402L, 501L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.TRIGGER_SOURCE, 401L, 9L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 502L, 9L);

		LinkChannelEditingService.PreparationResult preparation = LinkChannelEditingService.prepareConfirmedSetChannel(
			level,
			null,
			LinkNodeType.CORE,
			501L,
			9L,
			false,
			false
		);

		assertTrue(preparation.successful());
		assertTrue(preparation.plan().hasChanges());
		assertEquals(2, preparation.plan().changedTriggerSourceCount());
		assertEquals(
			Map.of(
				401L, Set.of(501L, 502L),
				402L, Set.of()
			),
			collectTargetsByTriggerSource(preparation.plan())
		);

		LinkChannelEditingService.ApplyResult applyResult = LinkChannelEditingService.applyPreparedSetChannel(preparation.plan());
		assertEquals(2, applyResult.appliedOperationCount());
		assertEquals(1, applyResult.currentLinkedPeerCount());
		assertEquals(LinkConnectionMode.CHANNEL, savedData.getConnectionMode(LinkNodeType.CORE, 501L));
		assertEquals(9L, savedData.getChannel(LinkNodeType.CORE, 501L));
		assertEquals(Set.of(501L, 502L), savedData.getLinkedCoresByTriggerSource(401L));
		assertTrue(savedData.getLinkedCoresByTriggerSource(402L).isEmpty());
		assertEquals(Set.of(401L), savedData.getLinkedTriggerSourcesByCore(501L));
	}

	/**
	 * 同一批把多个 core 一起切进同一频道时，应按整批最终状态只生成一次去重后的来源重排计划。
	 */
	@Test
	void batchCoreChannelUpdatesShouldResolveFinalChannelStateOnce(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 601L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 701L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 702L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 703L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.TRIGGER_SOURCE, 601L, 11L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 703L, 11L);

		LinkChannelEditingService.BatchPreparationResult preparation = LinkChannelEditingService.prepareConfirmedBatchSetChannel(
			level,
			null,
			List.of(
				new ChannelOverride(LinkNodeType.CORE, 701L, 11L),
				new ChannelOverride(LinkNodeType.CORE, 702L, 11L)
			),
			false,
			false
		);

		assertTrue(preparation.successful());
		assertTrue(preparation.plan().hasChanges());
		assertEquals(2, preparation.plan().changedChannelNodeCount());
		assertEquals(1, preparation.plan().changedTriggerSourceCount());
		assertEquals(1, preparation.plan().preparedOperations().size());
		assertEquals(Set.of(701L, 702L, 703L), preparation.plan().preparedOperations().get(0).targets());

		LinkChannelEditingService.BatchApplyResult applyResult = LinkChannelEditingService.applyPreparedBatchSetChannel(preparation.plan());
		assertEquals(1, applyResult.appliedOperationCount());
		assertEquals(2, applyResult.changedChannelNodeCount());
		assertEquals(LinkConnectionMode.CHANNEL, savedData.getConnectionMode(LinkNodeType.CORE, 701L));
		assertEquals(LinkConnectionMode.CHANNEL, savedData.getConnectionMode(LinkNodeType.CORE, 702L));
		assertEquals(11L, savedData.getChannel(LinkNodeType.CORE, 701L));
		assertEquals(11L, savedData.getChannel(LinkNodeType.CORE, 702L));
		assertEquals(Set.of(701L, 702L, 703L), savedData.getLinkedCoresByTriggerSource(601L));
	}

	/**
	 * 把 prepared replace 结果按来源序号收束，便于断言 core 编辑影响到的来源集合。
	 */
	private static Map<Long, Set<Long>> collectTargetsByTriggerSource(LinkChannelEditingService.PreparedChannelUpdate plan) {
		Map<Long, Set<Long>> targetsByTriggerSource = new LinkedHashMap<>();
		for (LinkSetExecutionService.PreparedReplaceOperation operation : plan.preparedOperations()) {
			targetsByTriggerSource.put(operation.sourceSerial(), new LinkedHashSet<>(operation.targets()));
		}
		return targetsByTriggerSource;
	}

	/**
	 * 构造一份可供 SavedData 工厂复用的最小 ServerLevel。
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
	 * 只实现本轮测试真正会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			LinkChannelEditingServiceTest.class.getClassLoader(),
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
		Field field = owner.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	/**
	 * 读取 Unsafe，避免重复内联反射样板。
	 */
	private static Unsafe unsafe() throws Exception {
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
}
