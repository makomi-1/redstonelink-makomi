package com.makomi.util;

import com.makomi.data.NodeAliasDisplayUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 展示文本列表格式化工具。
 * <p>
 * 用于将 `别名(#序号)` / `#序号` 列表按 `/` 连接，并按字符数或像素宽度做截断与换行。
 * </p>
 */
public final class DisplayTextListFormatUtil {
	private static final String EMPTY_TEXT = "-";
	private static final String SEPARATOR = "/";
	private static final String ELLIPSIS = "...";

	private DisplayTextListFormatUtil() {
	}

	/**
	 * 按字符数上限构建单行文本。
	 *
	 * @param displayTexts 展示文本列表
	 * @param maxChars 最大字符数
	 * @return 截断后的展示文本
	 */
	public static String buildText(Collection<String> displayTexts, int maxChars) {
		return buildText(displayTexts, maxChars, String::length);
	}

	/**
	 * 按自定义度量上限构建单行文本。
	 *
	 * @param displayTexts 展示文本列表
	 * @param maxUnits 最大度量值
	 * @param measure 文本度量器
	 * @return 截断后的展示文本
	 */
	public static String buildText(Collection<String> displayTexts, int maxUnits, ToIntFunction<String> measure) {
		List<String> normalizedDisplayTexts = normalizeDisplayTexts(displayTexts);
		if (normalizedDisplayTexts.isEmpty() || maxUnits <= 0 || measure == null) {
			return EMPTY_TEXT;
		}

		int shownItems = normalizedDisplayTexts.size();
		while (shownItems > 0) {
			String base = String.join(SEPARATOR, normalizedDisplayTexts.subList(0, shownItems));
			int remaining = normalizedDisplayTexts.size() - shownItems;
			String suffix = remaining > 0 ? SerialDisplayFormatUtil.buildRemainingSuffix(remaining) : "";
			String text = base + suffix;
			if (measure.applyAsInt(text) <= maxUnits) {
				return text;
			}
			if (shownItems == 1) {
				String truncated = truncateTextWithSuffix(normalizedDisplayTexts.get(0), suffix, maxUnits, measure);
				if (!truncated.isEmpty()) {
					return truncated + suffix;
				}
			}
			shownItems -= 1;
		}

		String suffixOnly = SerialDisplayFormatUtil.buildRemainingSuffix(normalizedDisplayTexts.size());
		return measure.applyAsInt(suffixOnly) <= maxUnits ? suffixOnly : EMPTY_TEXT;
	}

	/**
	 * 按自定义度量上限构建多行文本。
	 *
	 * @param displayTexts 展示文本列表
	 * @param maxUnits 单行最大度量值
	 * @param maxItems 最多参与展示的条目数
	 * @param measure 文本度量器
	 * @return 已换行的展示文本
	 */
	public static List<String> buildWrappedLines(
		Collection<String> displayTexts,
		int maxUnits,
		int maxItems,
		ToIntFunction<String> measure
	) {
		List<String> normalizedDisplayTexts = normalizeDisplayTexts(displayTexts);
		if (normalizedDisplayTexts.isEmpty() || maxUnits <= 0 || maxItems <= 0 || measure == null) {
			return List.of();
		}

		int displayCount = Math.min(normalizedDisplayTexts.size(), maxItems);
		List<String> lines = new ArrayList<>();
		StringBuilder currentLine = new StringBuilder();
		for (int index = 0; index < displayCount; index++) {
			String item = normalizedDisplayTexts.get(index);
			String candidate = currentLine.isEmpty() ? item : currentLine + SEPARATOR + item;
			if (measure.applyAsInt(candidate) <= maxUnits) {
				currentLine.setLength(0);
				currentLine.append(candidate);
				continue;
			}

			if (currentLine.length() > 0) {
				lines.add(currentLine.toString());
				currentLine.setLength(0);
			}
			if (measure.applyAsInt(item) <= maxUnits) {
				currentLine.append(item);
				continue;
			}
			lines.add(truncateText(item, maxUnits, measure));
		}
		if (currentLine.length() > 0) {
			lines.add(currentLine.toString());
		}

		int remaining = normalizedDisplayTexts.size() - displayCount;
		if (remaining > 0) {
			appendRemainingSuffix(lines, remaining, maxUnits, measure);
		}
		return lines.isEmpty() ? List.of() : List.copyOf(lines);
	}

	/**
	 * 按给定度量截断单条文本，必要时追加省略号。
	 *
	 * @param text 原文本
	 * @param maxUnits 最大度量值
	 * @param measure 文本度量器
	 * @return 截断后的文本
	 */
	public static String truncateText(String text, int maxUnits, ToIntFunction<String> measure) {
		if (text == null || text.isBlank() || maxUnits <= 0 || measure == null) {
			return EMPTY_TEXT;
		}
		String normalizedText = text.trim();
		if (measure.applyAsInt(normalizedText) <= maxUnits) {
			return normalizedText;
		}
		if (measure.applyAsInt(ELLIPSIS) > maxUnits) {
			return EMPTY_TEXT;
		}
		int end = normalizedText.length();
		while (end > 0 && measure.applyAsInt(normalizedText.substring(0, end) + ELLIPSIS) > maxUnits) {
			end -= 1;
		}
		return end <= 0 ? EMPTY_TEXT : normalizedText.substring(0, end) + ELLIPSIS;
	}

	private static List<String> normalizeDisplayTexts(Collection<String> displayTexts) {
		if (displayTexts == null || displayTexts.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String displayText : displayTexts) {
			String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(displayText);
			if (!normalizedDisplayText.isEmpty()) {
				normalized.add(normalizedDisplayText);
			}
		}
		return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
	}

	private static String truncateTextWithSuffix(
		String text,
		String suffix,
		int maxUnits,
		ToIntFunction<String> measure
	) {
		if (suffix == null || suffix.isEmpty()) {
			return truncateText(text, maxUnits, measure);
		}
		if (measure.applyAsInt(suffix) >= maxUnits || measure.applyAsInt(ELLIPSIS + suffix) > maxUnits) {
			return "";
		}
		String normalizedText = NodeAliasDisplayUtil.normalizeAlias(text);
		int end = normalizedText.length();
		while (end > 0 && measure.applyAsInt(normalizedText.substring(0, end) + ELLIPSIS + suffix) > maxUnits) {
			end -= 1;
		}
		return end <= 0 ? "" : normalizedText.substring(0, end) + ELLIPSIS;
	}

	private static void appendRemainingSuffix(
		List<String> lines,
		int remaining,
		int maxUnits,
		ToIntFunction<String> measure
	) {
		String suffix = SerialDisplayFormatUtil.buildRemainingSuffix(remaining);
		if (lines.isEmpty()) {
			lines.add(measure.applyAsInt(suffix) <= maxUnits ? suffix : truncateText(suffix, maxUnits, measure));
			return;
		}
		int lastIndex = lines.size() - 1;
		String lastLine = lines.get(lastIndex);
		String combined = lastLine + suffix;
		if (measure.applyAsInt(combined) <= maxUnits) {
			lines.set(lastIndex, combined);
			return;
		}
		if (measure.applyAsInt(suffix) <= maxUnits) {
			lines.add(suffix);
			return;
		}
		lines.add(truncateText(suffix, maxUnits, measure));
	}
}
