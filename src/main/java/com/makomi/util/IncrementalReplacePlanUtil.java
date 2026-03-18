package com.makomi.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 增量覆盖计划工具。
 * <p>
 * 用于将“覆盖式写入”拆分为最小增量操作（新增/移除/保持），
 * 以便各业务模块复用统一差异计算逻辑。
 * </p>
 */
public final class IncrementalReplacePlanUtil {
	private IncrementalReplacePlanUtil() {
	}

	/**
	 * 计算集合覆盖差异计划。
	 *
	 * @param before 覆盖前集合（允许 null）
	 * @param after 覆盖后目标集合（允许 null）
	 * @param <T> 元素类型
	 * @return 差异计划（新增/移除/保持）
	 */
	public static <T> SetReplacePlan<T> buildSetReplacePlan(Collection<T> before, Collection<T> after) {
		Set<T> beforeSet = normalize(before);
		Set<T> afterSet = normalize(after);

		Set<T> toAdd = new HashSet<>(afterSet);
		toAdd.removeAll(beforeSet);

		Set<T> toRemove = new HashSet<>(beforeSet);
		toRemove.removeAll(afterSet);

		Set<T> unchanged = new HashSet<>(beforeSet);
		unchanged.retainAll(afterSet);

		return new SetReplacePlan<>(Set.copyOf(toAdd), Set.copyOf(toRemove), Set.copyOf(unchanged));
	}

	/**
	 * 归一化输入集合：过滤 null 并去重。
	 */
	private static <T> Set<T> normalize(Collection<T> values) {
		if (values == null || values.isEmpty()) {
			return Set.of();
		}
		Set<T> normalized = new HashSet<>();
		for (T value : values) {
			if (value != null) {
				normalized.add(value);
			}
		}
		return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
	}

	/**
	 * 集合覆盖差异计划。
	 *
	 * @param toAdd 需要新增的元素
	 * @param toRemove 需要移除的元素
	 * @param unchanged 保持不变的元素
	 * @param <T> 元素类型
	 */
	public record SetReplacePlan<T>(Set<T> toAdd, Set<T> toRemove, Set<T> unchanged) {
		/**
		 * @return 新增数量
		 */
		public int addedCount() {
			return toAdd.size();
		}

		/**
		 * @return 移除数量
		 */
		public int removedCount() {
			return toRemove.size();
		}

		/**
		 * @return 变更数量（新增+移除）
		 */
		public int changedCount() {
			return addedCount() + removedCount();
		}

		/**
		 * @return 是否有变更
		 */
		public boolean changed() {
			return changedCount() > 0;
		}
	}
}
