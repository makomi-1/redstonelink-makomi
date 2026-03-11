package com.makomi.item;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 物品提示文本截断工具。
 * <p>
 * 按最大字符数拼接目标列表，超出时追加 “…(+N)” 提示。
 * </p>
 */
public final class TooltipTextTruncateUtil {
	/**
	 * 默认 tooltip 文本最大字符数。
	 */
	public static final int DEFAULT_TOOLTIP_MAX_CHARS = 48;

	private TooltipTextTruncateUtil() {
	}

	/**
	 * 根据最大字符数构建目标列表文本，超出时追加 “…(+N)”。
	 *
	 * @param targets 目标序列号列表
	 * @param maxChars 最大允许字符数
	 * @return 处理后的文本，空列表返回 "-"
	 */
	public static String buildTargetsText(List<Long> targets, int maxChars) {
		if (targets == null || targets.isEmpty() || maxChars <= 0) {
			return "-";
		}
		List<String> items = targets.stream().map(String::valueOf).collect(Collectors.toList());
		int total = items.size();
		int displayCount = total;
		// 逐步减少显示条目数，直到带上 “…(+N)” 的文本长度不超限。
		while (displayCount > 0) {
			String base = String.join(",", items.subList(0, displayCount));
			int remaining = total - displayCount;
			String text = remaining > 0 ? base + buildRemainingSuffix(remaining) : base;
			if (text.length() <= maxChars) {
				return text;
			}
			displayCount -= 1;
		}
		String suffixOnly = buildRemainingSuffix(total);
		if (suffixOnly.length() <= maxChars) {
			return suffixOnly;
		}
		return "-";
	}

	/**
	 * 生成“剩余数量”提示文本，格式为 “…(+N)”。
	 *
	 * @param remaining 剩余数量
	 * @return 提示文本
	 */
	private static String buildRemainingSuffix(int remaining) {
		return "…(+" + remaining + ")";
	}
}
