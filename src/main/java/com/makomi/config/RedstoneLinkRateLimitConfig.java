package com.makomi.config;

/**
 * 命令频率防护配置。
 */
public record RedstoneLinkRateLimitConfig(
	boolean enabled,
	int windowTicks,
	int globalCapacity,
	int tierBaseCapacity,
	int tierStepPerLevel,
	int actorBaseCapacity,
	int actorStepPerLevel,
	int actorGroupLinkRwBaseCapacity,
	int actorGroupLinkRwStepPerLevel,
	int actorGroupGraphWriteBaseCapacity,
	int actorGroupGraphWriteStepPerLevel,
	int actorGroupCrossChunkBaseCapacity,
	int actorGroupCrossChunkStepPerLevel,
	int actorGroupOtherBaseCapacity,
	int actorGroupOtherStepPerLevel
) {
	/**
	 * 按权限等级计算层级窗口容量。
	 */
	public int tierCapacity(int permissionLevel) {
		return resolveCapacity(tierBaseCapacity, tierStepPerLevel, permissionLevel);
	}

	/**
	 * 按权限等级计算个体窗口容量。
	 */
	public int actorCapacity(int permissionLevel) {
		return resolveCapacity(actorBaseCapacity, actorStepPerLevel, permissionLevel);
	}

	/**
	 * 按权限等级计算 `link` 组个体窗口容量。
	 */
	public int actorLinkRwCapacity(int permissionLevel) {
		return resolveCapacity(actorGroupLinkRwBaseCapacity, actorGroupLinkRwStepPerLevel, permissionLevel);
	}

	/**
	 * 按权限等级计算 `graph` 保存组个体窗口容量。
	 */
	public int actorGraphWriteCapacity(int permissionLevel) {
		return resolveCapacity(actorGroupGraphWriteBaseCapacity, actorGroupGraphWriteStepPerLevel, permissionLevel);
	}

	/**
	 * 按权限等级计算 `crosschunk` 组个体窗口容量。
	 */
	public int actorCrossChunkCapacity(int permissionLevel) {
		return resolveCapacity(actorGroupCrossChunkBaseCapacity, actorGroupCrossChunkStepPerLevel, permissionLevel);
	}

	/**
	 * 按权限等级计算 `other` 组个体窗口容量。
	 */
	public int actorOtherCapacity(int permissionLevel) {
		return resolveCapacity(actorGroupOtherBaseCapacity, actorGroupOtherStepPerLevel, permissionLevel);
	}

	/**
	 * 根据权限等级解析分层限流容量。
	 */
	private static int resolveCapacity(int baseCapacity, int stepPerLevel, int permissionLevel) {
		int clampedLevel = Math.max(0, Math.min(4, permissionLevel));
		long resolved = (long) baseCapacity + (long) stepPerLevel * clampedLevel;
		return (int) Math.max(1L, resolved);
	}
}
