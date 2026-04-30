package com.makomi.testsupport;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * 26.1 测试兼容辅助。
 * <p>
 * 统一封装新版 NBT / SavedData / BlockEntity 持久化入口，避免各测试重复散落兼容逻辑。
 * </p>
 */
public final class TestMinecraftSupport {
	private static HolderLookup.Provider lookupProvider;

	private TestMinecraftSupport() {}

	/**
	 * 提前引导 Minecraft 基础注册表，避免测试类在访问 `Level/Items/Blocks` 时触发“Not bootstrapped”。
	 */
	public static synchronized void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 返回测试共用的内建注册表查询上下文。
	 */
	public static synchronized HolderLookup.Provider lookupProvider() {
		bootstrapMinecraft();
		if (lookupProvider == null) {
			lookupProvider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
		}
		return lookupProvider;
	}

	/**
	 * 使用 26.1 新入口保存方块实体的自定义 NBT。
	 */
	public static CompoundTag saveBlockEntityCustomOnly(BlockEntity blockEntity) {
		return blockEntity.saveCustomOnly(lookupProvider());
	}

	/**
	 * 使用 26.1 新入口加载方块实体的自定义 NBT。
	 */
	public static void loadBlockEntityCustomOnly(BlockEntity blockEntity, CompoundTag tag) {
		blockEntity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, lookupProvider(), tag));
	}

	/**
	 * 在测试中短暂恢复 block / block entity intrusive holder 写窗。
	 */
	public static <T> T withWritableBlockRegistries(Supplier<T> supplier) {
		bootstrapMinecraft();
		try (RegistryWriteWindow ignored = RegistryWriteWindow.open()) {
			return supplier.get();
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to open temporary intrusive holder write window", ex);
		}
	}

	/**
	 * 为未注册测试方块创建只做 `isValid(state)` 校验的临时 block entity type。
	 */
	@SuppressWarnings("unchecked")
	public static <T extends BlockEntity> BlockEntityType<T> createPlaceholderBlockEntityType(Block... validBlocks) {
		try {
			Class<?> supplierClass = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier");
			Constructor<BlockEntityType> constructor = (Constructor<BlockEntityType>) BlockEntityType.class.getDeclaredConstructor(
				supplierClass,
				Set.class
			);
			constructor.setAccessible(true);
			Object supplier = Proxy.newProxyInstance(
				supplierClass.getClassLoader(),
				new Class<?>[] { supplierClass },
				(proxy, method, args) -> null
			);
			return (BlockEntityType<T>) constructor.newInstance(supplier, Set.of(validBlocks));
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to create placeholder block entity type", ex);
		}
	}

	/**
	 * 通过反射调用 SavedData 私有 `toTag()`，保留原测试“只校验序列化结果”的语义。
	 */
	public static CompoundTag saveSavedData(SavedData savedData) {
		try {
			Method method = resolveDeclaredMethod(savedData.getClass(), "toTag");
			method.setAccessible(true);
			return (CompoundTag) method.invoke(savedData);
		} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
			throw new IllegalStateException("failed to invoke SavedData.toTag by reflection", ex);
		}
	}

	/**
	 * 通过反射调用私有静态 `load(CompoundTag)`。
	 */
	public static <T> T invokePrivateStaticLoad(Class<?> owner, Class<T> resultType, CompoundTag tag) {
		return invokePrivateStaticLoad(owner, resultType, "load", tag);
	}

	/**
	 * 通过反射调用指定名称的私有静态 `method(CompoundTag)`。
	 */
	public static <T> T invokePrivateStaticLoad(
		Class<?> owner,
		Class<T> resultType,
		String methodName,
		CompoundTag tag
	) {
		try {
			Method method = resolveDeclaredMethod(owner, methodName, CompoundTag.class);
			method.setAccessible(true);
			return resultType.cast(method.invoke(null, tag));
		} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
			throw new IllegalStateException("failed to invoke private static load method by reflection", ex);
		}
	}

	/**
	 * 创建带默认 lookup 上下文的 SavedData 存储，适配 26.1 构造器签名。
	 */
	public static SavedDataStorage createSavedDataStorage(Path path) {
		return new SavedDataStorage(path, null, lookupProvider());
	}

	/**
	 * 将 ResourceKey 统一转成可持久化的维度标识字符串。
	 */
	public static String dimensionId(ResourceKey<Level> dimension) {
		return dimension.identifier().toString();
	}

	/**
	 * 兼容新版 `Optional<long[]>` 读取语义。
	 */
	public static long[] getLongArrayOrEmpty(CompoundTag tag, String key) {
		return tag.getLongArray(key).orElseGet(() -> new long[0]);
	}

	/**
	 * 兼容 long array / long list 两种持久化编码。
	 */
	public static List<Long> getLongValuesOrEmpty(CompoundTag tag, String key) {
		if (tag == null) {
			return List.of();
		}
		java.util.Optional<long[]> longArray = tag.getLongArray(key);
		if (longArray.isPresent()) {
			return java.util.Arrays.stream(longArray.get()).boxed().toList();
		}
		return toLongList(tag.getListOrEmpty(key));
	}

	/**
	 * 兼容新版 `getListOrEmpty(...)` 读取语义。
	 */
	public static ListTag getListOrEmpty(CompoundTag tag, String key) {
		return tag == null ? new ListTag() : tag.getListOrEmpty(key);
	}

	/**
	 * 兼容新版 `Optional<CompoundTag>` 列表读取语义。
	 */
	public static CompoundTag getCompoundOrEmpty(ListTag listTag, int index) {
		return listTag.getCompoundOrEmpty(index);
	}

	/**
	 * 将长整型 NBT 列表转为稳定断言用集合。
	 */
	public static List<Long> toLongList(ListTag listTag) {
		return listTag
			.stream()
			.filter(LongTag.class::isInstance)
			.map(LongTag.class::cast)
			.map(LongTag::longValue)
			.toList();
	}

	/**
	 * 构造当前项目使用的“单 float 槽位”自定义模型数据。
	 */
	public static CustomModelData singleFloatCustomModelData(int value) {
		return new CustomModelData(List.of((float) value), List.of(), List.of(), List.of());
	}

	/**
	 * 解析 SNBT 为 CompoundTag。
	 */
	public static CompoundTag parseCompoundTag(String content) throws CommandSyntaxException {
		return TagParser.parseCompoundFully(content);
	}

	private static Method resolveDeclaredMethod(Class<?> owner, String methodName, Class<?>... parameterTypes)
		throws NoSuchMethodException {
		Class<?> current = owner;
		while (current != null) {
			try {
				return current.getDeclaredMethod(methodName, parameterTypes);
			} catch (NoSuchMethodException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchMethodException(methodName);
	}

	private static final class RegistryWriteWindow implements AutoCloseable {
		private final RegistryState[] states;

		private RegistryWriteWindow(RegistryState... states) {
			this.states = states;
		}

		private static RegistryWriteWindow open() throws ReflectiveOperationException {
			return new RegistryWriteWindow(
				RegistryState.open((MappedRegistry<?>) BuiltInRegistries.BLOCK),
				RegistryState.open((MappedRegistry<?>) BuiltInRegistries.BLOCK_ENTITY_TYPE)
			);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			for (int index = states.length - 1; index >= 0; index--) {
				states[index].close();
			}
		}
	}

	private static final class RegistryState implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final boolean frozen;
		private final Map<?, ?> intrusiveHolders;

		private RegistryState(MappedRegistry<?> registry, boolean frozen, Map<?, ?> intrusiveHolders) {
			this.registry = registry;
			this.frozen = frozen;
			this.intrusiveHolders = intrusiveHolders;
		}

		private static RegistryState open(MappedRegistry<?> registry) throws ReflectiveOperationException {
			boolean previousFrozen = FROZEN_FIELD.getBoolean(registry);
			Map<?, ?> previousIntrusiveHolders = (Map<?, ?>) UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.get(registry);
			FROZEN_FIELD.setBoolean(registry, false);
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(
				registry,
				previousIntrusiveHolders == null ? new IdentityHashMap<>() : previousIntrusiveHolders
			);
			return new RegistryState(registry, previousFrozen, previousIntrusiveHolders);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(registry, intrusiveHolders);
			FROZEN_FIELD.setBoolean(registry, frozen);
		}
	}

	private static final Field FROZEN_FIELD = field("frozen");
	private static final Field UNREGISTERED_INTRUSIVE_HOLDERS_FIELD = field("unregisteredIntrusiveHolders");

	private static Field field(String name) {
		try {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}
}
