package com.makomi.api.v1.model;

/**
 * API 前置事件回调的决策结果。
 * <p>
 * `allowed=false` 表示拒绝继续执行，并可携带原因 key。
 * </p>
 */
public record ApiDecision(boolean allowed, String reasonKey) {
	/**
	 * 允许执行。
	 */
	public static ApiDecision allow() {
		return new ApiDecision(true, "");
	}

	/**
	 * 拒绝执行，并附带原因 key。
	 */
	public static ApiDecision deny(String reasonKey) {
		return new ApiDecision(false, reasonKey == null ? "" : reasonKey);
	}
}

