package com.makomi.command.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.item.PairableItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/**
 * LinkCommandSupport 物品快照同步回归测试。
 */
@Tag("stable-core")
class LinkCommandSupportTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 当指定 `core` 命中刷新集合时，其物品 tooltip 快照应改写为最新 triggerSource 集合。
	 */
	@Test
	void syncItemListLinkSnapshotsShouldRefreshMatchingCoreItems(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		LinkSavedData savedData = LinkSavedData.get(level);

		savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, 101L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 201L);
		savedData.markSerialAllocated(LinkNodeType.CORE, 202L);
		savedData.addTriggerSourceCoreLink(101L, 201L);

		ItemStack refreshedCoreStack = createTestCoreStack();
		LinkItemData.setSerial(refreshedCoreStack, 201L);
		LinkItemData.setLinkedSerials(refreshedCoreStack, Set.of(999L));

		ItemStack untouchedCoreStack = createTestCoreStack();
		LinkItemData.setSerial(untouchedCoreStack, 202L);
		LinkItemData.setLinkedSerials(untouchedCoreStack, Set.of(888L));

		boolean changed = LinkCommandSupport.syncItemListLinkSnapshots(
			level,
			List.of(refreshedCoreStack, untouchedCoreStack),
			LinkNodeType.CORE,
			Set.of(201L)
		);

		assertTrue(changed);
		assertEquals(List.of(101L), LinkItemData.getLinkedSerials(refreshedCoreStack));
		assertEquals(List.of(888L), LinkItemData.getLinkedSerials(untouchedCoreStack));
	}

	/**
	 * 批量快照收集器应同时登记真实来源 `triggerSource` 与受影响 `core` 物品快照刷新请求。
	 */
	@Test
	void batchCollectorShouldRegisterAffectedCoreItemSnapshots(@TempDir Path tempDir) throws Exception {
		ServerLevel level = createServerLevel(tempDir);
		ServerPlayer player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
		LinkSetExecutionService.PreparedReplaceOperation operation = new LinkSetExecutionService.PreparedReplaceOperation(
			level,
			player,
			LinkNodeType.TRIGGER_SOURCE,
			101L,
			LinkNodeType.CORE,
			Set.of(201L, 202L),
			Set.of(202L, 203L),
			List.of(),
			1,
			false
		);
		LinkCommandSupport.BatchLinkSnapshotSyncCollector collector = new LinkCommandSupport.BatchLinkSnapshotSyncCollector(level);

		collector.collectPreparedReplace(operation);

		Field field = LinkCommandSupport.BatchLinkSnapshotSyncCollector.class.getDeclaredField("playerItemSnapshotSyncRequests");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<Object> requests = (Set<Object>) field.get(collector);
		Map<LinkNodeType, Set<Long>> serialsByType = new LinkedHashMap<>();
		for (Object request : requests) {
			LinkNodeType nodeType = (LinkNodeType) invokeNoArg(request, "nodeType");
			long serial = (long) invokeNoArg(request, "serial");
			serialsByType.computeIfAbsent(nodeType, ignored -> new LinkedHashSet<>()).add(serial);
		}

		assertEquals(Set.of(101L), serialsByType.get(LinkNodeType.TRIGGER_SOURCE));
		assertEquals(Set.of(201L, 202L, 203L), serialsByType.get(LinkNodeType.CORE));
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
		setField(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, level));
		return level;
	}

	/**
	 * 构造一份可参与 `core` 快照同步的最小测试物品栈。
	 * <p>
	 * 这里复用原版已注册的 `Items.STONE` 栈底座，只把 `item` 视图替换成带
	 * `PairableItem` 能力的测试物品，避免在测试阶段再次触发注册表初始化。
	 * </p>
	 */
	private static ItemStack createTestCoreStack() throws Exception {
		ItemStack stack = new ItemStack(Items.STONE);
		setField(ItemStack.class, stack, "item", createTestCoreItem());
		return stack;
	}

	/**
	 * 基于已注册物品拷贝字段，拼出一个仅暴露节点类型能力的最小测试物品。
	 */
	private static Item createTestCoreItem() throws Exception {
		TestPairableItem item = (TestPairableItem) unsafe().allocateInstance(TestPairableItem.class);
		copyInstanceFields(Item.class, Items.STONE, item);
		setField(TestPairableItem.class, item, "nodeType", LinkNodeType.CORE);
		return item;
	}

	/**
	 * 最小化的可配对测试物品，仅暴露节点类型能力，避免拉起方块注册依赖。
	 */
	private static final class TestPairableItem extends Item implements PairableItem {
		private final LinkNodeType nodeType;

		private TestPairableItem() {
			super(new Item.Properties());
			throw new UnsupportedOperationException("仅供 Unsafe.allocateInstance 使用");
		}

		@Override
		public LinkNodeType getNodeType() {
			return nodeType;
		}
	}

	/**
	 * 复制指定父类上的实例字段，复用原版物品的稳定底座状态。
	 */
	private static void copyInstanceFields(Class<?> owner, Object source, Object target) throws Exception {
		for (Field field : owner.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			field.setAccessible(true);
			field.set(target, field.get(source));
		}
	}

	/**
	 * 只实现本轮测试真正会访问到的 levelData 读接口。
	 */
	private static Object createLevelDataProxy() throws Exception {
		Class<?> levelDataType = Level.class.getDeclaredField("levelData").getType();
		Class<?> serverLevelDataType = ServerLevel.class.getDeclaredField("serverLevelData").getType();
		return Proxy.newProxyInstance(
			LinkCommandSupportTest.class.getClassLoader(),
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
	 * 读取无参 record 访问器。
	 */
	private static Object invokeNoArg(Object target, String methodName) throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(target);
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
