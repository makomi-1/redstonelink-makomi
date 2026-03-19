package com.makomi.util;

/**
 * 红石信号强度归一化工具。
 * <p>
 * 统一约束输入强度到原版红石有效范围 `0~15`，
 * 避免各模块分散实现导致边界规则漂移。
 * </p>
 */
public final class SignalStrengths {
	private static final int MIN_STRENGTH = 0;
	private static final int MAX_STRENGTH = 15;

	private SignalStrengths() {
	}

	/**
	 * 归一化信号强度到 `0~15`。
	 *
	 * @param signalStrength 原始输入强度
	 * @return 归一化后的有效强度
	 */
	public static int clamp(int signalStrength) {
		return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, signalStrength));
	}
}
