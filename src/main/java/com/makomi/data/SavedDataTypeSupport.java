package com.makomi.data;

import com.makomi.RedstoneLink;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 26.1 持久化数据类型适配 helper。
 * <p>
 * 26.1 起 `SavedDataType` 需要显式传入 `Identifier`，
 * 这里统一收口，避免各持久化类重复拼装命名空间。
 * </p>
 */
final class SavedDataTypeSupport {
	private SavedDataTypeSupport() {
	}

	/**
	 * 构造主世界级持久化数据类型定义。
	 */
	static <T extends SavedData> SavedDataType<T> levelType(String dataName, Supplier<T> factory, Codec<T> codec) {
		return new SavedDataType<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, dataName),
			factory,
			codec,
			DataFixTypes.LEVEL
		);
	}
}
