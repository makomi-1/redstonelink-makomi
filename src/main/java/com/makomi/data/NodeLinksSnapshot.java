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
 * 同时携带读取时的 revision 基线，供 GUI/编辑器做乐观并发校验。
 * </p>
 */
public record NodeLinksSnapshot(
	NodeIdentitySnapshot sourceIdentity,
	List<Long> visibleTargets,
	List<String> visibleTargetDisplayTexts,
	boolean masked,
	long graphRevision,
	long sourceRevision,
	long coreRevision
) {
	public NodeLinksSnapshot {
		sourceIdentity = sourceIdentity == null
			? new NodeIdentitySnapshot(LinkNodeType.CORE, 0L, false, false, false, null, null)
			: sourceIdentity;
		visibleTargets = normalizeTargets(visibleTargets);
		visibleTargetDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(visibleTargets, visibleTargetDisplayTexts);
		graphRevision = Math.max(0L, graphRevision);
		sourceRevision = Math.max(0L, sourceRevision);
		coreRevision = Math.max(0L, coreRevision);
	}

	public NodeLinksSnapshot(
		NodeIdentitySnapshot sourceIdentity,
		List<Long> visibleTargets,
		boolean masked,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		this(sourceIdentity, visibleTargets, List.of(), masked, graphRevision, sourceRevision, coreRevision);
	}

	/**
	 * 兼容旧调用方仅传入 `graphRevision/sourceRevision` 的场景。
	 */
	public NodeLinksSnapshot(
		NodeIdentitySnapshot sourceIdentity,
		List<Long> visibleTargets,
		List<String> visibleTargetDisplayTexts,
		boolean masked,
		long graphRevision,
		long sourceRevision
	) {
		this(sourceIdentity, visibleTargets, visibleTargetDisplayTexts, masked, graphRevision, sourceRevision, 0L);
	}

	/**
	 * 兼容旧调用方仅传入 `graphRevision/sourceRevision` 的场景。
	 */
	public NodeLinksSnapshot(
		NodeIdentitySnapshot sourceIdentity,
		List<Long> visibleTargets,
		boolean masked,
		long graphRevision,
		long sourceRevision
	) {
		this(sourceIdentity, visibleTargets, List.of(), masked, graphRevision, sourceRevision, 0L);
	}

	/**
	 * 兼容仅关心可见目标列表与展示文本的调用方。
	 */
	public NodeLinksSnapshot(
		NodeIdentitySnapshot sourceIdentity,
		List<Long> visibleTargets,
		List<String> visibleTargetDisplayTexts,
		boolean masked
	) {
		this(sourceIdentity, visibleTargets, visibleTargetDisplayTexts, masked, 0L, 0L, 0L);
	}

	/**
	 * 兼容仅关心可见目标列表的旧调用方。
	 */
	public NodeLinksSnapshot(NodeIdentitySnapshot sourceIdentity, List<Long> visibleTargets, boolean masked) {
		this(sourceIdentity, visibleTargets, List.of(), masked, 0L, 0L, 0L);
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
