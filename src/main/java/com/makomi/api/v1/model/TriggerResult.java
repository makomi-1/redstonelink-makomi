package com.makomi.api.v1.model;

/**
 * 触发执行结果。
 */
public record TriggerResult(
	boolean success,
	boolean cancelled,
	String reasonKey,
	int totalTargets,
	int triggeredCount,
	int skippedMissingCount,
	int skippedOfflineCount,
	int skippedUnsupportedCount
) {
	public TriggerResult {
		reasonKey = reasonKey == null ? "" : reasonKey;
	}

	/**
	 * 生成成功结果。
	 */
	public static TriggerResult success(
		int totalTargets,
		int triggeredCount,
		int skippedMissingCount,
		int skippedOfflineCount,
		int skippedUnsupportedCount
	) {
		return new TriggerResult(
			true,
			false,
			"",
			totalTargets,
			triggeredCount,
			skippedMissingCount,
			skippedOfflineCount,
			skippedUnsupportedCount
		);
	}

	/**
	 * 生成取消结果（由前置事件拦截）。
	 */
	public static TriggerResult cancelled(String reasonKey, int totalTargets) {
		return new TriggerResult(false, true, reasonKey, totalTargets, 0, 0, 0, 0);
	}

	/**
	 * 生成失败结果（参数非法、上下文非法等）。
	 */
	public static TriggerResult failed(String reasonKey) {
		return new TriggerResult(false, false, reasonKey, 0, 0, 0, 0, 0);
	}
}

