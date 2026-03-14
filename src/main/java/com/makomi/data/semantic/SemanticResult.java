package com.makomi.data.semantic;

/**
 * 语义解析/校验统一结果。
 *
 * @param value 结果值；失败时为 null
 * @param error 错误类型
 * @param <T> 结果值类型
 */
public record SemanticResult<T>(T value, SemanticError error) {
	/**
	 * 创建成功结果。
	 *
	 * @param value 成功值
	 * @param <T> 值类型
	 * @return 成功结果
	 */
	public static <T> SemanticResult<T> success(T value) {
		return new SemanticResult<>(value, SemanticError.NONE);
	}

	/**
	 * 创建失败结果。
	 *
	 * @param error 错误类型
	 * @param <T> 值类型
	 * @return 失败结果
	 */
	public static <T> SemanticResult<T> failure(SemanticError error) {
		return new SemanticResult<>(null, error == null ? SemanticError.INVALID_TYPE : error);
	}

	/**
	 * @return 是否成功
	 */
	public boolean isSuccess() {
		return error == SemanticError.NONE && value != null;
	}
}
