package com.makomi.data;

/**
 * 联动节点类型。
 * <p>
 * CORE 表示目标核心节点，TRIGGER_SOURCE 表示触发源节点。
 * </p>
 */
public enum LinkNodeType {
	CORE,
	TRIGGER_SOURCE;

	/**
	 * 将语义类型名称解析为节点类型。
	 *
	 * @param name 语义类型名称（仅支持 triggerSource/core，大小写不敏感）
	 * @return 解析结果；无法识别时回退为 {@link #CORE}
	 */
	public static LinkNodeType fromName(String name) {
		return LinkNodeSemantics.tryParseCanonicalType(name).orElse(CORE);
	}
}
