package com.makomi.config;

/**
 * 跨区块重试配置。
 */
public record RedstoneLinkCrossChunkRetryConfig(
	int warnThreshold,
	int errorThreshold,
	int dropThreshold,
	int stage1MaxAttempts,
	int stage1IntervalTicks,
	int stage2MaxAttempts,
	int stage2IntervalTicks,
	int stage3MaxAttempts,
	int stage3IntervalTicks,
	int stage4IntervalTicks
) {
	/**
	 * 解析持久 pending 当前失败次数所在的重试阶段。
	 */
	public int persistentStageIndex(int attempts) {
		int normalizedAttempts = Math.max(1, attempts);
		if (normalizedAttempts <= stage1MaxAttempts) {
			return 1;
		}
		if (normalizedAttempts <= stage2MaxAttempts) {
			return 2;
		}
		if (normalizedAttempts <= stage3MaxAttempts) {
			return 3;
		}
		return 4;
	}

	/**
	 * 解析持久 pending 当前失败次数对应的重试间隔。
	 */
	public int persistentIntervalTicks(int attempts) {
		return switch (persistentStageIndex(attempts)) {
			case 1 -> stage1IntervalTicks;
			case 2 -> stage2IntervalTicks;
			case 3 -> stage3IntervalTicks;
			default -> stage4IntervalTicks;
		};
	}
}
