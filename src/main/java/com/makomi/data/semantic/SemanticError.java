package com.makomi.data.semantic;

/**
 * 语义校验错误类型。
 */
public enum SemanticError {
	/**
	 * 成功，无错误。
	 */
	NONE,
	/**
	 * 类型文本无法解析。
	 */
	INVALID_TYPE,
	/**
	 * 类型与角色语义不匹配。
	 */
	ROLE_NOT_ALLOWED,
	/**
	 * 类型不在当前配置允许集合内。
	 */
	CONFIG_NOT_ALLOWED
}
