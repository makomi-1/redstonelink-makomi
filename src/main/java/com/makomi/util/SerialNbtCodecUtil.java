package com.makomi.util;

import java.util.Collection;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 序号集合 NBT 编解码工具。
 * <p>
 * 统一处理 long array 与序号集合之间的转换，保证读取时仅接受正整数，
 * 写入时按升序稳定输出，避免重复实现。
 * </p>
 */
 public final class SerialNbtCodecUtil {
	private SerialNbtCodecUtil() {
	}

	/**
	 * 从 NBT long array 读取序号集合（仅保留正整数）。
	 *
	 * @param tag NBT 容器
	 * @param key long array 键名
	 * @param output 输出集合
	 */
	public static void readSerialSet(CompoundTag tag, String key, Set<Long> output) {
		if (tag == null || output == null || key == null || key.isBlank()) {
			return;
		}
		if (!tag.contains(key, Tag.TAG_LONG_ARRAY)) {
			return;
		}
		for (long serial : tag.getLongArray(key)) {
			if (serial > 0L) {
				output.add(serial);
			}
		}
	}

	/**
	 * 序号集合转升序 long[]，用于稳定持久化。
	 *
	 * @param serials 输入序号集合
	 * @return 升序 long[]
	 */
	public static long[] toSortedLongArray(Collection<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return new long[0];
		}
		return serials.stream()
			.filter(value -> value != null && value > 0L)
			.mapToLong(Long::longValue)
			.sorted()
			.toArray();
	}
}
