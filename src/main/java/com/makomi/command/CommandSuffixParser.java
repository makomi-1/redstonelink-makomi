package com.makomi.command;

/**
 * 命令后缀解析工具。
 * <p>
 * 统一处理 confirm 与 resident/confirm 两类后缀，减少各命令注册器的重复实现。
 * </p>
 */
public final class CommandSuffixParser {
	private static final String CONFIRM_SUFFIX = " confirm";
	private static final String RESIDENT_SUFFIX = " resident";

	private CommandSuffixParser() {
	}

	/**
	 * 解析仅包含 confirm 的后缀场景。
	 *
	 * @param rawText 原始参数文本
	 * @param ignoreCase 是否大小写不敏感
	 * @return 解析结果
	 */
	public static ConfirmSuffixParseResult parseConfirmOnly(String rawText, boolean ignoreCase) {
		String normalized = normalize(rawText);
		if (endsWith(normalized, CONFIRM_SUFFIX, ignoreCase)) {
			String payload = normalized.substring(0, normalized.length() - CONFIRM_SUFFIX.length()).trim();
			if (!payload.isEmpty()) {
				return new ConfirmSuffixParseResult(payload, true);
			}
		}
		return new ConfirmSuffixParseResult(normalized, false);
	}

	/**
	 * 解析 resident + confirm 的组合后缀场景。
	 * <p>
	 * 后缀可按任意顺序出现，每个后缀最多消费一次。
	 * </p>
	 *
	 * @param rawText 原始参数文本
	 * @param ignoreCase 是否大小写不敏感
	 * @return 解析结果
	 */
	public static ResidentConfirmSuffixParseResult parseResidentAndConfirm(String rawText, boolean ignoreCase) {
		String normalized = normalize(rawText);
		boolean confirmed = false;
		boolean resident = false;
		boolean consumed;
		do {
			consumed = false;
			if (!confirmed && endsWith(normalized, CONFIRM_SUFFIX, ignoreCase)) {
				confirmed = true;
				normalized = normalized.substring(0, normalized.length() - CONFIRM_SUFFIX.length()).trim();
				consumed = true;
			}
			if (!resident && endsWith(normalized, RESIDENT_SUFFIX, ignoreCase)) {
				resident = true;
				normalized = normalized.substring(0, normalized.length() - RESIDENT_SUFFIX.length()).trim();
				consumed = true;
			}
		} while (consumed);

		if (normalized.isEmpty()) {
			return new ResidentConfirmSuffixParseResult("", resident, false);
		}
		return new ResidentConfirmSuffixParseResult(normalized, resident, confirmed);
	}

	/**
	 * 标准化命令参数文本，统一处理空输入。
	 */
	private static String normalize(String rawText) {
		return rawText == null ? "" : rawText.trim();
	}

	/**
	 * 按需执行大小写敏感或不敏感的后缀匹配。
	 */
	private static boolean endsWith(String text, String suffix, boolean ignoreCase) {
		if (text == null || suffix == null) {
			return false;
		}
		if (!ignoreCase) {
			return text.endsWith(suffix);
		}
		int textLength = text.length();
		int suffixLength = suffix.length();
		if (textLength < suffixLength) {
			return false;
		}
		return text.regionMatches(true, textLength - suffixLength, suffix, 0, suffixLength);
	}

	/**
	 * confirm-only 后缀解析结果。
	 *
	 * @param payload 去除后缀后的参数文本
	 * @param confirmed 是否携带 confirm 后缀
	 */
	public record ConfirmSuffixParseResult(String payload, boolean confirmed) {}

	/**
	 * resident + confirm 组合后缀解析结果。
	 *
	 * @param payload 去除后缀后的参数文本
	 * @param resident 是否携带 resident 后缀
	 * @param confirmed 是否携带 confirm 后缀
	 */
	public record ResidentConfirmSuffixParseResult(String payload, boolean resident, boolean confirmed) {}
}
