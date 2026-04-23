package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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
 * 节点退役协调器回归测试。
 */
@Tag("stable-core")
class LinkRetireCoordinatorTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 节点退役应同步释放别名占用，使同名别名可立即分配给其它节点。
	 */
	@Test
	void retireAndSyncWhitelistShouldReleaseAliasReservation(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		NodeAliasSavedData aliasSavedData = NodeAliasSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.CORE, 11L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 12L);
		assertTrue(aliasSavedData.upsert(LinkNodeType.CORE, 11L, "中控A").changed());

		LinkSavedData.RetireResult retireResult = LinkRetireCoordinator.retireAndSyncWhitelist(level, LinkNodeType.CORE, 11L);

		assertTrue(retireResult.retiredMarked());
		assertTrue(aliasSavedData.getAlias(LinkNodeType.CORE, 11L).isEmpty());
		assertTrue(aliasSavedData.resolveAllByAlias("中控A").isEmpty());
		assertTrue(aliasSavedData.upsert(LinkNodeType.CORE, 12L, "中控A").changed());
		assertEquals(12L, aliasSavedData.resolveSerial(LinkNodeType.CORE, "中控A").orElseThrow());
	}

	private static ServerLevel createServerLevel(Path tempDir) throws Exception {
		Unsafe unsafe = unsafe();
		ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
		DedicatedServer server = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
		ServerChunkCache chunkCache = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
		DimensionDataStorage dataStorage = new DimensionDataStorage(tempDir.toFile(), null, null);
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

	/**
	 * 只实现本测试会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			LinkRetireCoordinatorTest.class.getClassLoader(),
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
