package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.data.GraphWriteJsonSupport.GraphWriteRequest;
import com.makomi.data.GraphWriteJsonSupport.RenameNodeAliasOperation;
import com.makomi.data.GraphWriteJsonSupport.ReplaceTriggerSourceTargetsOperation;
import com.makomi.data.GraphWriteJsonSupport.SetNodeChannelOperation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/**
 * GraphWriteService 混合保存编排回归测试。
 */
@Tag("stable-core")
class GraphWriteServiceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 同一请求里若 core 会先被切回 serial，则显式 replace 应允许把它作为目标写入。
	 */
	@Test
	void preparePlanShouldAllowReplaceTargetsReturningToSerialInSameRequest(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 201L, 7L);

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-1",
			"snapshot-1",
			"serial",
			savedData.graphRevision(),
			List.of(
				new SetNodeChannelOperation(
					LinkNodeType.CORE,
					201L,
					0L,
					savedData.coreRevision(201L),
					0L
				),
				new ReplaceTriggerSourceTargetsOperation(
					101L,
					savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, 101L),
					List.of(201L)
				)
			)
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData);

		assertTrue(readBoolean(preparedPlan, "successful"));
		assertEquals("", readString(preparedPlan, "failureResponseJson"));
		List<?> validatedReplaceOperations = readList(preparedPlan, "validatedReplaceOperations");
		assertEquals(1, validatedReplaceOperations.size());
		assertEquals("", readString(validatedReplaceOperations.get(0), "failureResponseJson"));
		assertTrue(readBoolean(validatedReplaceOperations.get(0), "actualGraphWrite"));
	}

	/**
	 * 同一来源若同时存在频道派生 replace 与显式 replace，应以后者为最终结果。
	 */
	@Test
	void applyPlanShouldLetExplicitReplaceOverrideDerivedChannelReplace(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.TRIGGER_SOURCE, 101L, 9L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 201L, 9L);
		LinkSavedDataChannelSupport.putChannelConfig(savedData, LinkNodeType.CORE, 202L, 9L);

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-2",
			"snapshot-2",
			"serial",
			savedData.graphRevision(),
			List.of(
				new SetNodeChannelOperation(
					LinkNodeType.CORE,
					202L,
					0L,
					savedData.coreRevision(202L),
					0L
				),
				new ReplaceTriggerSourceTargetsOperation(
					101L,
					savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, 101L),
					List.of(202L)
				)
			)
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData);
		assertTrue(readBoolean(preparedPlan, "successful"));

		Object validatedChannelBatchOperation = invokeNoArg(preparedPlan, "validatedChannelBatchOperation");
		assertNotNull(validatedChannelBatchOperation);
		LinkChannelEditingService.PreparedChannelBatchUpdate channelPlan =
			(LinkChannelEditingService.PreparedChannelBatchUpdate) invokeNoArg(validatedChannelBatchOperation, "plan");
		assertNotNull(channelPlan);
		assertEquals(0, channelPlan.preparedOperations().size());
		assertEquals(1, channelPlan.changedChannelNodeCount());

		if (channelPlan.hasChanges()) {
			LinkChannelEditingService.applyPreparedBatchSetChannel(channelPlan);
		}
		for (Object validatedReplaceOperation : readList(preparedPlan, "validatedReplaceOperations")) {
			if (!readBoolean(validatedReplaceOperation, "actualGraphWrite")) {
				continue;
			}
			LinkSetExecutionService.PreparedReplaceOperation preparedReplaceOperation =
				(LinkSetExecutionService.PreparedReplaceOperation) invokeNoArg(validatedReplaceOperation, "operation");
			LinkSetExecutionService.applyPreparedReplace(preparedReplaceOperation);
		}

		assertEquals(LinkConnectionMode.SERIAL, savedData.getConnectionMode(LinkNodeType.TRIGGER_SOURCE, 101L));
		assertEquals(LinkConnectionMode.SERIAL, savedData.getConnectionMode(LinkNodeType.CORE, 202L));
		assertEquals(Set.of(202L), savedData.getLinkedCoresByTriggerSource(101L));
	}

	/**
	 * graph 网页功能权限被关闭时，即使只是别名修改，也应整体拒绝网页保存请求。
	 */
	@Test
	void preparePlanShouldRejectWhenGraphWebFeaturePermissionMissing(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-3",
			"snapshot-3",
			"serial",
			savedData.graphRevision(),
			List.of(new RenameNodeAliasOperation(LinkNodeType.TRIGGER_SOURCE, 101L, "门口开关"))
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData, false, true, true);

		assertTrue(!readBoolean(preparedPlan, "successful"));
		assertTrue(readString(preparedPlan, "failureResponseJson").contains("当前没有使用图编辑网页的权限"));
	}

	/**
	 * 无关对象推动 graphRevision 后，replace 仍应按目标 triggerSource 的 sourceRevision 判定，而不是整图误拒绝。
	 */
	@Test
	void preparePlanShouldAllowReplaceWhenOnlyUnrelatedTopologyChanged(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 102L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);

		long baseGraphRevision = savedData.graphRevision();
		long expectedSourceRevision = savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, 101L);

		savedData.replaceTriggerSourceTargets(102L, Set.of(202L));

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-4",
			"snapshot-4",
			"serial",
			baseGraphRevision,
			List.of(new ReplaceTriggerSourceTargetsOperation(101L, expectedSourceRevision, List.of(201L)))
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData);

		assertTrue(readBoolean(preparedPlan, "successful"));
		assertEquals("", readString(preparedPlan, "failureResponseJson"));
		List<?> validatedReplaceOperations = readList(preparedPlan, "validatedReplaceOperations");
		assertEquals(1, validatedReplaceOperations.size());
		assertEquals("", readString(validatedReplaceOperations.get(0), "failureResponseJson"));
		assertTrue(readBoolean(validatedReplaceOperations.get(0), "actualGraphWrite"));
	}

	/**
	 * 无关对象推动 graphRevision 后，频道修改也应继续按节点 revision 判定，而不是整图误拒绝。
	 */
	@Test
	void preparePlanShouldAllowChannelUpdateWhenOnlyUnrelatedTopologyChanged(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 102L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);

		long baseGraphRevision = savedData.graphRevision();
		long expectedCoreRevision = savedData.coreRevision(201L);

		savedData.replaceTriggerSourceTargets(102L, Set.of(202L));

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-5",
			"snapshot-5",
			"serial",
			baseGraphRevision,
			List.of(new SetNodeChannelOperation(LinkNodeType.CORE, 201L, 0L, expectedCoreRevision, 7L))
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData);

		assertTrue(readBoolean(preparedPlan, "successful"));
		assertEquals("", readString(preparedPlan, "failureResponseJson"));
		Object validatedChannelBatchOperation = invokeNoArg(preparedPlan, "validatedChannelBatchOperation");
		assertNotNull(validatedChannelBatchOperation);
		assertEquals("", readString(validatedChannelBatchOperation, "failureResponseJson"));
		LinkChannelEditingService.PreparedChannelBatchUpdate channelPlan =
			(LinkChannelEditingService.PreparedChannelBatchUpdate) invokeNoArg(validatedChannelBatchOperation, "plan");
		assertNotNull(channelPlan);
		assertEquals(1, channelPlan.changedChannelNodeCount());
	}

	/**
	 * 同一 triggerSource 在保存前已被其他操作修改时，仍应返回来源级真实冲突。
	 */
	@Test
	void preparePlanShouldKeepSourceConflictWhenEditedTriggerSourceChanged(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);

		long baseGraphRevision = savedData.graphRevision();
		long expectedSourceRevision = savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, 101L);

		savedData.replaceTriggerSourceTargets(101L, Set.of(202L));

		GraphWriteRequest request = new GraphWriteRequest(
			"draft-6",
			"snapshot-6",
			"serial",
			baseGraphRevision,
			List.of(new ReplaceTriggerSourceTargetsOperation(101L, expectedSourceRevision, List.of(201L)))
		);

		Object preparedPlan = invokePreparePlan(request, level, savedData);

		assertTrue(!readBoolean(preparedPlan, "successful"));
		assertTrue(readString(preparedPlan, "failureResponseJson").contains("source_revision_conflict"));
	}

	/**
	 * 通过反射构造 ResolvedRequestContext 并执行 preparePlan，避免测试里重复拼接完整网络入口。
	 */
	private static Object invokePreparePlan(
		GraphWriteRequest request,
		ServerLevel level,
		LinkSavedData savedData
	) throws Exception {
		return invokePreparePlan(request, level, savedData, true, true, true);
	}

	/**
	 * 按指定权限位构造 ResolvedRequestContext 并执行 preparePlan。
	 */
	private static Object invokePreparePlan(
		GraphWriteRequest request,
		ServerLevel level,
		LinkSavedData savedData,
		boolean hasGraphFeaturePermission,
		boolean hasGraphEditPermission,
		boolean hasAliasEditPermission
	) throws Exception {
		Class<?> contextClass = Class.forName("com.makomi.data.GraphWriteService$ResolvedRequestContext");
		Constructor<?> constructor = contextClass.getDeclaredConstructor(
			GraphWriteRequest.class,
			ServerLevel.class,
			LinkSavedData.class,
			ServerPlayer.class,
			CommandSourceStack.class,
			boolean.class,
			boolean.class,
			boolean.class,
			boolean.class,
			boolean.class
		);
		constructor.setAccessible(true);
		Object requestContext = constructor.newInstance(
			request,
			level,
			savedData,
			null,
			null,
			hasGraphFeaturePermission,
			hasGraphEditPermission,
			hasAliasEditPermission,
			false,
			false
		);
		Method preparePlan = GraphWriteService.class.getDeclaredMethod("preparePlan", contextClass);
		preparePlan.setAccessible(true);
		return preparePlan.invoke(null, requestContext);
	}

	private static boolean readBoolean(Object target, String methodName) throws Exception {
		return (boolean) invokeNoArg(target, methodName);
	}

	private static String readString(Object target, String methodName) throws Exception {
		return (String) invokeNoArg(target, methodName);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> readList(Object target, String methodName) throws Exception {
		return (List<Object>) invokeNoArg(target, methodName);
	}

	private static Object invokeNoArg(Object target, String methodName) throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(target);
	}

	/**
	 * 构造一份可供 SavedData 工厂复用的最小 ServerLevel。
	 */
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
	 * 只实现本轮测试真正会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			GraphWriteServiceTest.class.getClassLoader(),
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
