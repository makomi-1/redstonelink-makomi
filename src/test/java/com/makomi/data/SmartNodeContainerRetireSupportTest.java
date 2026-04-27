package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.testsupport.TestMinecraftSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/**
 * 智能节点容器销毁递归退役回归测试。
 */
@Tag("stable-core")
class SmartNodeContainerRetireSupportTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 容器内部的普通节点销毁后应递归退役。
	 */
	@Test
	void retireContainedNodeStacksShouldRetireNestedCoreAndTriggerSource(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.CORE, 11L);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 21L);

		int retiredCount = SmartNodeContainerRetireSupport.retireTargets(
			level,
			List.of(
				new SmartNodeContainerRetireSupport.RetireTarget(LinkNodeType.CORE, 11L),
				new SmartNodeContainerRetireSupport.RetireTarget(LinkNodeType.TRIGGER_SOURCE, 21L)
			)
		);

		assertEquals(2, retiredCount);
		assertTrue(savedData.isSerialRetired(LinkNodeType.CORE, 11L));
		assertTrue(savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, 21L));
	}

	/**
	 * 容器内部的转发器销毁后应同时退役 `core/triggerSource` 双身份并清除统一序号标记。
	 */
	@Test
	void retireContainedNodeStacksShouldRetireRepeaterDualIdentity(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.CORE, 31L);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 31L);
		savedData.markRepeaterSerial(31L);

		int retiredCount = SmartNodeContainerRetireSupport.retireTargets(
			level,
			List.of(new SmartNodeContainerRetireSupport.RetireTarget(LinkNodeType.CORE, 31L))
		);

		assertEquals(1, retiredCount);
		assertTrue(savedData.isSerialRetired(LinkNodeType.CORE, 31L));
		assertTrue(savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, 31L));
		assertFalse(savedData.isRepeaterSerial(31L));
	}

	private static ServerLevel createServerLevel(Path tempDir) throws Exception {
		Unsafe unsafe = unsafe();
		ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
		DedicatedServer server = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
		ServerChunkCache chunkCache = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
		DimensionDataStorage dataStorage = TestMinecraftSupport.createDimensionDataStorage(tempDir);
		Object levelDataProxy = createLevelDataProxy();

		setField(Level.class, level, "isClientSide", false);
		setField(Level.class, level, "dimension", Level.OVERWORLD);
		setField(Level.class, level, "levelData", levelDataProxy);
		setField(ServerLevel.class, level, "server", server);
		setField(ServerLevel.class, level, "serverLevelData", levelDataProxy);
		setField(ServerLevel.class, level, "chunkSource", chunkCache);
		setField(ServerChunkCache.class, chunkCache, "level", level);
		setField(ServerChunkCache.class, chunkCache, "dataStorage", dataStorage);
		setField(MinecraftServer.class, server, "levels", java.util.Map.of(Level.OVERWORLD, level));
		return level;
	}

	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			SmartNodeContainerRetireSupportTest.class.getClassLoader(),
			new Class<?>[] { levelDataType, serverLevelDataType },
			(proxy, method, args) -> switch (method.getName()) {
				case "getGameTime", "getDayTime" -> 0L;
				case "isHardcore", "isFlatWorld", "isRaining", "isThundering" -> false;
				default -> defaultValue(method.getReturnType());
			}
		);
	}

	private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
		Field field = owner.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object defaultValue(Class<?> returnType) {
		if (returnType == null || !returnType.isPrimitive()) {
			return null;
		}
		if (returnType == boolean.class) {
			return false;
		}
		if (returnType == byte.class) {
			return (byte) 0;
		}
		if (returnType == short.class) {
			return (short) 0;
		}
		if (returnType == int.class) {
			return 0;
		}
		if (returnType == long.class) {
			return 0L;
		}
		if (returnType == float.class) {
			return 0F;
		}
		if (returnType == double.class) {
			return 0D;
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
}
