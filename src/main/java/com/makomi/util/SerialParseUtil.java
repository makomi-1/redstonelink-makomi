package com.makomi.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 序号解析与基础过滤工具，供 GUI 与命令侧复用。
 */
public final class SerialParseUtil {
	private SerialParseUtil() {
	}

	/**
	 * 解析序号列表文本（逗号/分号/空白分隔），并过滤非正整数。
	 *
	 * @param rawText 输入文本
	 * @param maxTargetCount 目标数量上限（<=0 表示不限制）
	 * @return 解析结果（包含无效项与是否超限）
	 */
	public static TargetParseResult parseTargets(String rawText, int maxTargetCount) {
		String text = rawText == null ? "" : rawText.trim();
		if (text.isEmpty()) {
			return new TargetParseResult(Set.of(), List.of(), false);
		}

		// 与命令侧保持一致，支持逗号/分号/空白混合作为分隔符。
		String[] tokens = text.split("[,;\\s]+");
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		List<String> invalidEntries = new ArrayList<>();
		int limit = maxTargetCount <= 0 ? Integer.MAX_VALUE : maxTargetCount;
		for (String token : tokens) {
			if (token.isBlank()) {
				continue;
			}
			if (!token.matches("[0-9]+")) {
				invalidEntries.add(token);
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(token);
			} catch (NumberFormatException ignored) {
				invalidEntries.add(token);
				continue;
			}
			if (serial <= 0L) {
				invalidEntries.add(token);
				continue;
			}
			result.add(serial);
			if (result.size() > limit) {
				return new TargetParseResult(Set.of(), List.copyOf(invalidEntries), true);
			}
		}
		return new TargetParseResult(Set.copyOf(result), List.copyOf(invalidEntries), false);
	}

	/**
	 * 序号解析结果。
	 *
	 * @param targets 解析后的序号集合
	 * @param invalidEntries 无效条目
	 * @param exceedLimit 是否超过上限
	 */
	public record TargetParseResult(Set<Long> targets, List<String> invalidEntries, boolean exceedLimit) {
	}
}
