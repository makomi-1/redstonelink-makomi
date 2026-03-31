package com.makomi.client.screen;

import com.makomi.util.SerialDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * 序号输入语法支持工具。
 * <p>
 * 统一处理客户端序号输入框的文本规范化、非法分段收集与结构化回显，
 * 避免各 GUI 分散维护相同语义。
 * </p>
 */
final class SerialInputSyntaxSupport {
	private SerialInputSyntaxSupport() {
	}

	/**
	 * 将目标序号列表转换为结构化表达式。
	 */
	static String joinTargets(List<Long> targets) {
		if (targets == null || targets.isEmpty()) {
			return "";
		}
		return SerialDisplayFormatUtil.buildExpression(targets).joinAll();
	}

	/**
	 * 规范化客户端输入文本。
	 * <p>
	 * 多行输入会被折叠为 `/` 分段，保持与现有序号解析语义一致。
	 * </p>
	 */
	static String normalizeExpression(String rawText) {
		if (rawText == null) {
			return "";
		}
		String normalizedLineBreaks = rawText.replace("\r\n", "\n").replace('\r', '\n');
		String[] lines = normalizedLineBreaks.split("\n", -1);
		List<String> nonEmptyLines = new ArrayList<>(lines.length);
		for (String rawLine : lines) {
			String line = rawLine == null ? "" : rawLine.trim();
			if (!line.isEmpty()) {
				nonEmptyLines.add(line);
			}
		}
		return String.join("/", nonEmptyLines);
	}

	/**
	 * 校验序号表达式，仅做语法级判断，不做数量裁决。
	 */
	static ValidationResult validate(String rawText) {
		String normalizedExpression = normalizeExpression(rawText);
		if (normalizedExpression.isEmpty()) {
			return new ValidationResult("", List.of());
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(normalizedExpression, 0);
		return new ValidationResult(normalizedExpression, parseResult.invalidEntries());
	}

	/**
	 * 序号输入语法校验结果。
	 */
	record ValidationResult(String normalizedExpression, List<String> invalidEntries) {
		ValidationResult {
			normalizedExpression = normalizedExpression == null ? "" : normalizedExpression;
			invalidEntries = List.copyOf(invalidEntries == null ? List.of() : invalidEntries);
		}

		/**
		 * @return 当前是否为空表达式
		 */
		boolean empty() {
			return normalizedExpression.isEmpty();
		}

		/**
		 * @return 当前是否通过语法校验
		 */
		boolean valid() {
			return invalidEntries.isEmpty();
		}
	}
}
