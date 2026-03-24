package com.makomi.data;

import com.makomi.util.SerialCollectionFormatUtil;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 节点当前连接可见视图快照。
 * <p>
 * 该 DTO 表示“在当前读取上下文下，节点可见的当前连接集合”，
 * 不承载原始未脱敏结果，避免外层误用。
 * </p>
 */
public record NodeLinksSnapshot(NodeIdentitySnapshot sourceIdentity, List<Long> visibleTargets, boolean masked) {
	public NodeLinksSnapshot {
		sourceIdentity = sourceIdentity == null
			? new NodeIdentitySnapshot(LinkNodeType.CORE, 0L, false, false, false, null, null)
			: sourceIdentity;
		visibleTargets = normalizeTargets(visibleTargets);
	}

	/**
	 * 可见目标数量。
	 */
	public int visibleTargetCount() {
		return visibleTargets.size();
	}

	/**
	 * 当前可见目标集合。
	 * <p>
	 * 供物品 NBT 等必须写入集合结构的路径复用统一归一化结果，
	 * 避免外层重复从 `List` 手动转换。
	 * </p>
	 */
	public Set<Long> visibleTargetSet() {
		if (visibleTargets.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(visibleTargets));
	}

	private static List<Long> normalizeTargets(Collection<Long> targets) {
		List<Long> normalized = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(targets);
		return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
	}
}
