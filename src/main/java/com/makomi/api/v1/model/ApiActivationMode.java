package com.makomi.api.v1.model;

import com.makomi.block.entity.ActivationMode;

/**
 * RedstoneLink 对外暴露的触发模式。
 */
public enum ApiActivationMode {
	TOGGLE,
	PULSE;

	/**
	 * 转换为内部触发模式。
	 */
	public ActivationMode toInternal() {
		return this == PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
	}

	/**
	 * 从内部触发模式转换为对外触发模式。
	 */
	public static ApiActivationMode fromInternal(ActivationMode mode) {
		return mode == ActivationMode.PULSE ? PULSE : TOGGLE;
	}
}

