package com.makomi.data;

import com.makomi.util.SignalStrengths;

/**
 * 过滤器持久化配置快照。
 */
public record LinkFilterConfigSnapshot(
	String serialExpression,
	LinkFilterTargetMode targetMode,
	long channel,
	LinkFilterNodeSetMode nodeSetMode,
	LinkFilterSignalThresholdSource signalThresholdSource,
	int fixedSignalThreshold,
	LinkFilterSignalMode signalMode
) {
	/**
	 * 兼容旧序号模式构造入口。
	 */
	public LinkFilterConfigSnapshot(
		String serialExpression,
		LinkFilterNodeSetMode nodeSetMode,
		LinkFilterSignalThresholdSource signalThresholdSource,
		int fixedSignalThreshold,
		LinkFilterSignalMode signalMode
	) {
		this(serialExpression, LinkFilterTargetMode.SERIAL, 0L, nodeSetMode, signalThresholdSource, fixedSignalThreshold, signalMode);
	}

	public LinkFilterConfigSnapshot {
		serialExpression = serialExpression == null ? "" : serialExpression.trim();
		targetMode = resolveTargetMode(targetMode, serialExpression, channel);
		channel = targetMode == LinkFilterTargetMode.CHANNEL ? Math.max(0L, channel) : 0L;
		serialExpression = targetMode == LinkFilterTargetMode.SERIAL ? serialExpression : "";
		nodeSetMode = nodeSetMode == null ? LinkFilterNodeSetMode.DISABLED : nodeSetMode;
		signalThresholdSource = signalThresholdSource == null
			? LinkFilterSignalThresholdSource.FIXED_INPUT
			: signalThresholdSource;
		fixedSignalThreshold = SignalStrengths.clamp(fixedSignalThreshold);
		signalMode = signalMode == null ? LinkFilterSignalMode.DISABLED : signalMode;
	}

	/**
	 * 判断当前快照是否使用频道过滤目标。
	 */
	public boolean usesChannelTarget() {
		return targetMode == LinkFilterTargetMode.CHANNEL;
	}

	/**
	 * 判断当前快照是否使用序号过滤目标。
	 */
	public boolean usesSerialTarget() {
		return targetMode == LinkFilterTargetMode.SERIAL;
	}

	/**
	 * 解析默认过滤目标模式。
	 */
	private static LinkFilterTargetMode resolveTargetMode(
		LinkFilterTargetMode targetMode,
		String serialExpression,
		long channel
	) {
		if (targetMode != null) {
			return targetMode;
		}
		if ((serialExpression == null || serialExpression.isBlank()) && channel > 0L) {
			return LinkFilterTargetMode.CHANNEL;
		}
		return LinkFilterTargetMode.SERIAL;
	}
}
