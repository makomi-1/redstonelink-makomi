package com.makomi.data;

import com.makomi.util.SerialCollectionFormatUtil;
import java.util.Collection;
import java.util.List;

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

	private static List<Long> normalizeTargets(Collection<Long> targets) {
		List<Long> normalized = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(targets);
		return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
	}
}
