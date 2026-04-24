package com.makomi.data;

/**
 * 转发器配置快照。
 * <p>
 * 输入侧使用 `core` 视角表达“哪些 triggerSource 指向当前转发器”，
 * 输出侧使用 `triggerSource` 视角表达“当前转发器指向哪些 core”。
 * </p>
 */
public record RepeaterConfigSnapshot(
	String inputSerialExpression,
	String outputSerialExpression,
	RepeaterDelay delay
) {
	public RepeaterConfigSnapshot {
		inputSerialExpression = normalizeExpression(inputSerialExpression);
		outputSerialExpression = normalizeExpression(outputSerialExpression);
		delay = delay == null ? RepeaterDelay.ONE_TICK : delay;
	}

	/**
	 * @return 默认空配置
	 */
	public static RepeaterConfigSnapshot empty() {
		return new RepeaterConfigSnapshot("", "", RepeaterDelay.ONE_TICK);
	}

	private static String normalizeExpression(String rawExpression) {
		return rawExpression == null ? "" : rawExpression.trim();
	}
}
