package com.makomi.data;

import com.makomi.util.SignalStrengths;

/**
 * 过滤器持久化配置快照。
 */
public record LinkFilterConfigSnapshot(
	String serialExpression,
	LinkFilterNodeSetMode nodeSetMode,
	LinkFilterSignalThresholdSource signalThresholdSource,
	int fixedSignalThreshold,
	LinkFilterSignalMode signalMode
) {
	public LinkFilterConfigSnapshot {
		serialExpression = serialExpression == null ? "" : serialExpression;
		nodeSetMode = nodeSetMode == null ? LinkFilterNodeSetMode.DISABLED : nodeSetMode;
		signalThresholdSource = signalThresholdSource == null
			? LinkFilterSignalThresholdSource.FIXED_INPUT
			: signalThresholdSource;
		fixedSignalThreshold = SignalStrengths.clamp(fixedSignalThreshold);
		signalMode = signalMode == null ? LinkFilterSignalMode.DISABLED : signalMode;
	}
}
