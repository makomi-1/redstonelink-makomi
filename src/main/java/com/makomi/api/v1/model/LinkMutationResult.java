package com.makomi.api.v1.model;

import java.util.Set;

/**
 * 链接变更结果。
 */
public record LinkMutationResult(
	boolean success,
	boolean cancelled,
	String reasonKey,
	int addedCount,
	int removedCount,
	Set<Long> appliedTargets,
	Set<Long> rejectedTargets
) {
	public LinkMutationResult {
		reasonKey = reasonKey == null ? "" : reasonKey;
		appliedTargets = Set.copyOf(appliedTargets == null ? Set.of() : appliedTargets);
		rejectedTargets = Set.copyOf(rejectedTargets == null ? Set.of() : rejectedTargets);
	}

	/**
	 * 生成成功结果。
	 */
	public static LinkMutationResult success(
		int addedCount,
		int removedCount,
		Set<Long> appliedTargets,
		Set<Long> rejectedTargets
	) {
		return new LinkMutationResult(true, false, "", addedCount, removedCount, appliedTargets, rejectedTargets);
	}

	/**
	 * 生成取消结果（由前置事件拦截）。
	 */
	public static LinkMutationResult cancelled(String reasonKey) {
		return new LinkMutationResult(false, true, reasonKey, 0, 0, Set.of(), Set.of());
	}

	/**
	 * 生成失败结果（参数非法、上下文非法等）。
	 */
	public static LinkMutationResult failed(String reasonKey) {
		return new LinkMutationResult(false, false, reasonKey, 0, 0, Set.of(), Set.of());
	}
}

