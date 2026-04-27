package com.makomi.testsupport;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * 1.21.11 测试兼容辅助。
 * <p>
 * 统一封装新版 NBT / SavedData / BlockEntity 持久化入口，避免各测试重复散落兼容逻辑。
 * </p>
 */
public final class TestMinecraftSupport {
	private static final HolderLookup.Provider LOOKUP_PROVIDER = RegistryAccess
		.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
		.freeze();

	private TestMinecraftSupport() {}

	/**
	 * 返回测试共用的内建注册表查询上下文。
	 */
	public static HolderLookup.Provider lookupProvider() {
		return LOOKUP_PROVIDER;
	}

	/**
	 * 使用 1.21.11 新入口保存方块实体的自定义 NBT。
	 */
	public static CompoundTag saveBlockEntityCustomOnly(BlockEntity blockEntity) {
		return blockEntity.saveCustomOnly(lookupProvider());
	}

	/**
	 * 使用 1.21.11 新入口加载方块实体的自定义 NBT。
	 */
	public static void loadBlockEntityCustomOnly(BlockEntity blockEntity, CompoundTag tag) {
		blockEntity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, lookupProvider(), tag));
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
	 * 创建带默认 lookup 上下文的维度数据存储，适配 1.21.11 构造器签名。
	 */
	public static DimensionDataStorage createDimensionDataStorage(Path path) {
		return new DimensionDataStorage(path, null, lookupProvider());
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
}
