package com.makomi.config;

/**
 * 配置运行期辅助计算。
 * <p>
 * 负责由配置快照导出的轻量运行期计算，避免门面类继续累积细节逻辑。
 * </p>
 */
final class RedstoneLinkConfigRuntimeSupport {
	private RedstoneLinkConfigRuntimeSupport() {
	}

	/**
	 * 解析持久 pending 当前失败次数所在的分段索引。
	 */
	static int resolveCrossChunkRetryStageIndex(RedstoneLinkCrossChunkConfigValues values, int attempts) {
		if (values == null) {
			return 1;
		}
		int normalizedAttempts = Math.max(1, attempts);
		if (normalizedAttempts <= values.retryStage1MaxAttempts()) {
			return 1;
		}
		if (normalizedAttempts <= values.retryStage2MaxAttempts()) {
			return 2;
		}
		if (normalizedAttempts <= values.retryStage3MaxAttempts()) {
			return 3;
		}
		return 4;
	}

	/**
	 * 解析持久 pending 当前失败次数对应的重试间隔。
	 */
	static int resolveCrossChunkRetryIntervalTicks(RedstoneLinkCrossChunkConfigValues values, int attempts) {
		if (values == null) {
			return 1;
		}
		return switch (resolveCrossChunkRetryStageIndex(values, attempts)) {
			case 1 -> values.retryStage1IntervalTicks();
			case 2 -> values.retryStage2IntervalTicks();
			case 3 -> values.retryStage3IntervalTicks();
			default -> values.retryStage4IntervalTicks();
		};
	}

	/**
	 * 根据权限等级解析分层限流容量。
	 *
	 * @param baseCapacity 0 级基础容量
	 * @param stepPerLevel 每提升 1 级权限增加容量
	 * @param permissionLevel 权限等级（0~4）
	 * @return 对应权限等级容量，最小 1
	 */
	static int resolveRateLimitCapacity(int baseCapacity, int stepPerLevel, int permissionLevel) {
		int clampedLevel = Math.max(0, Math.min(4, permissionLevel));
		long resolved = (long) baseCapacity + (long) stepPerLevel * clampedLevel;
		return (int) Math.max(1L, resolved);
	}
}
