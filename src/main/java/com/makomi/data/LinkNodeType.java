package com.makomi.data;

/**
 * 联动节点类型。
 * <p>
 * CORE 表示目标核心节点，BUTTON 表示触发源节点。
 * </p>
 */
public enum LinkNodeType {
	CORE,
	BUTTON;

	/**
	 * 将字符串名称解析为节点类型。
	 *
	 * @param name 类型名称（大小写不敏感）
	 * @return 解析结果；无法识别时回退为 {@link #CORE}
	 */
	public static LinkNodeType fromName(String name) {
		for (LinkNodeType value : values()) {
			if (value.name().equalsIgnoreCase(name)) {
				return value;
			}
		}
		return CORE;
	}
}
