package com.makomi.block.entity;

/**
 * 可激活目标节点的触发模式。
 */
public enum ActivationMode {
	/** 切换模式：每次触发翻转状态。 */
	TOGGLE,
	/** 脉冲模式：触发后短暂激活并自动回落。 */
	PULSE;

	/**
	 * 按名称解析触发模式。
	 *
	 * @param name 模式名称（大小写不敏感）
	 * @return 匹配到的模式；无法匹配时返回默认 {@link #TOGGLE}
	 */
	public static ActivationMode fromName(String name) {
		for (ActivationMode value : values()) {
			if (value.name().equalsIgnoreCase(name)) {
				return value;
			}
		}
		return TOGGLE;
	}
}
