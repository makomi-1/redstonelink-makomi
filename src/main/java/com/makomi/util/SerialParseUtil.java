package com.makomi.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
	 * 解析序号列表文本（`/` 分段，支持 `N` 与 `A:B` 区间），并过滤非法段。
	 *
	 * @param rawText 输入文本
	 * @param maxTargetCount 目标数量上限（<=0 表示不限制）
	 * @return 解析结果（包含无效项、重复项与是否超限）
	 */
	public static TargetParseResult parseTargets(String rawText, int maxTargetCount) {
		String text = rawText == null ? "" : rawText.trim();
		if (text.isEmpty()) {
			return new TargetParseResult(Set.of(), List.of(), List.of(), false);
		}

		// 统一语法：使用 "/" 分段；末尾 "/" 形成的空段会被忽略。
		String[] tokens = text.split("/", -1);
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		LinkedHashMap<Long, Boolean> duplicateMap = new LinkedHashMap<>();
		List<String> invalidEntries = new ArrayList<>();
		int limit = maxTargetCount <= 0 ? Integer.MAX_VALUE : maxTargetCount;
		for (String rawToken : tokens) {
			String token = rawToken == null ? "" : rawToken.trim();
			if (token.isEmpty()) {
				continue;
			}

			SerialRange range = parseTokenToRange(token);
			if (range == null) {
				invalidEntries.add(rawToken);
				continue;
			}

			long current = range.startInclusive();
			while (current <= range.endInclusive()) {
				if (!result.add(current)) {
					duplicateMap.putIfAbsent(current, Boolean.TRUE);
				}
				if (result.size() > limit) {
					return new TargetParseResult(
						Set.of(),
						List.copyOf(invalidEntries),
						List.copyOf(duplicateMap.keySet()),
						true
					);
				}
				if (current == Long.MAX_VALUE) {
					break;
				}
				current++;
			}
		}
		return new TargetParseResult(
			Set.copyOf(result),
			List.copyOf(invalidEntries),
			List.copyOf(duplicateMap.keySet()),
			false
		);
	}

	/**
	 * 解析单个分段文本，支持单值 `N` 与区间 `A:B`。
	 *
	 * @param token 单个输入分段（已 trim）
	 * @return 解析后的闭区间；非法时返回 null
	 */
	private static SerialRange parseTokenToRange(String token) {
		int colonIndex = token.indexOf(':');
		if (colonIndex < 0) {
			Long value = parsePositiveLong(token);
			return value == null ? null : new SerialRange(value, value);
		}
		if (colonIndex != token.lastIndexOf(':')) {
			return null;
		}
		String left = token.substring(0, colonIndex);
		String right = token.substring(colonIndex + 1);
		Long start = parsePositiveLong(left);
		Long end = parsePositiveLong(right);
		if (start == null || end == null || start > end) {
			return null;
		}
		return new SerialRange(start, end);
	}

	/**
	 * 解析正整数字符串。
	 *
	 * @param text 待解析文本
	 * @return 正整数；非法或非正数时返回 null
	 */
	private static Long parsePositiveLong(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		if (!text.matches("[0-9]+")) {
			return null;
		}
		try {
			long value = Long.parseLong(text);
			return value > 0L ? value : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	/**
	 * 序号解析结果。
	 *
	 * @param targets 解析后的序号集合
	 * @param invalidEntries 无效条目
	 * @param duplicateEntries 重复输入条目（按首次检测顺序去重）
	 * @param exceedLimit 是否超过上限
	 */
	public record TargetParseResult(
		Set<Long> targets,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {
	}

	/**
	 * 闭区间序号段。
	 */
	private record SerialRange(long startInclusive, long endInclusive) {
	}
}
