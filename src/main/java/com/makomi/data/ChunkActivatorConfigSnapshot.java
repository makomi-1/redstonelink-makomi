package com.makomi.data;

/**
 * 区块激活器持久化配置快照。
 */
public record ChunkActivatorConfigSnapshot(
	String serialExpression,
	ChunkActivatorMode mode
) {
	public ChunkActivatorConfigSnapshot {
		serialExpression = serialExpression == null ? "" : serialExpression.trim();
		mode = mode == null ? ChunkActivatorMode.FORCE_LOAD : mode;
	}
}
