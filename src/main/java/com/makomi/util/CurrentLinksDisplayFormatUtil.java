package com.makomi.util;

import com.makomi.data.NodeAliasDisplayUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * “当前连接”摘要展示工具。
 * <p>
 * 该工具仅用于“当前连接”类文本展示：
 * 1. 纯 `#序号` 且连续的节点会压缩为 `#A:#B`；
 * 2. 任意带别名展示文本（如 `门厅(#3)`）会保持原样，并打断连续区间；
 * 3. 支持按字符数或像素宽度截断，并追加 `(+n)` 剩余数量提示。
 * </p>
 */
public final class CurrentLinksDisplayFormatUtil {
	private static final String EMPTY_TEXT = "-";
	private static final String SEPARATOR = "/";

	private CurrentLinksDisplayFormatUtil() {
	}

	/**
	 * 构建“当前连接”结构化表达式。
	 *
	 * @param serials 当前连接目标序号
	 * @param displayTexts 当前连接目标展示文本
	 * @return 结构化表达式
	 */
	public static StructuredExpression buildExpression(Collection<Long> serials, Collection<String> displayTexts) {
		List<Entry> entries = normalizeEntries(serials, displayTexts);
		if (entries.isEmpty()) {
			return StructuredExpression.empty();
		}

		List<String> segments = new ArrayList<>();
		List<Integer> segmentCounts = new ArrayList<>();
		Long rangeStart = null;
		Long previous = null;
		for (Entry entry : entries) {
			if (entry.canJoinRange()) {
				if (rangeStart == null) {
					rangeStart = entry.serial();
					previous = entry.serial();
					continue;
				}
				if (previous != null && entry.serial() == previous + 1L) {
					previous = entry.serial();
					continue;
				}
				appendRangeSegment(segments, segmentCounts, rangeStart, previous);
				rangeStart = entry.serial();
				previous = entry.serial();
				continue;
			}

			if (rangeStart != null && previous != null) {
				appendRangeSegment(segments, segmentCounts, rangeStart, previous);
				rangeStart = null;
				previous = null;
			}
			segments.add(entry.displayText());
			segmentCounts.add(1);
		}
		if (rangeStart != null && previous != null) {
			appendRangeSegment(segments, segmentCounts, rangeStart, previous);
		}
		return new StructuredExpression(List.copyOf(segments), List.copyOf(segmentCounts), entries.size());
	}

	/**
	 * 按字符数上限构建单行文本。
	 *
	 * @param serials 当前连接目标序号
	 * @param displayTexts 当前连接目标展示文本
	 * @param maxChars 最大字符数
	 * @return 单行文本
	 */
	public static String buildText(Collection<Long> serials, Collection<String> displayTexts, int maxChars) {
		return buildText(serials, displayTexts, maxChars, String::length);
	}

	/**
	 * 按给定度量上限构建单行文本。
	 *
	 * @param serials 当前连接目标序号
	 * @param displayTexts 当前连接目标展示文本
	 * @param maxUnits 最大度量值
	 * @param measure 文本度量器
	 * @return 单行文本
	 */
	public static String buildText(
		Collection<Long> serials,
		Collection<String> displayTexts,
		int maxUnits,
		ToIntFunction<String> measure
	) {
		StructuredExpression expression = buildExpression(serials, displayTexts);
		return buildText(expression, maxUnits, expression.segments().size(), measure);
	}

	/**
	 * 按给定度量上限构建单行文本。
	 *
	 * @param expression 结构化表达式
	 * @param maxUnits 最大度量值
	 * @param maxSegments 最大分段数
	 * @param measure 文本度量器
	 * @return 单行文本
	 */
	public static String buildText(
		StructuredExpression expression,
		int maxUnits,
		int maxSegments,
		ToIntFunction<String> measure
	) {
		if (expression == null || expression.isEmpty() || maxUnits <= 0 || maxSegments <= 0 || measure == null) {
			return EMPTY_TEXT;
		}

		int displaySegments = Math.min(expression.segments().size(), maxSegments);
		while (displaySegments > 0) {
			String base = String.join(SEPARATOR, expression.segments().subList(0, displaySegments));
			int omitted = countRemainingSerials(expression, displaySegments);
			String suffix = omitted > 0 ? SerialDisplayFormatUtil.buildRemainingSuffix(omitted) : "";
			String text = base + suffix;
			if (measure.applyAsInt(text) <= maxUnits) {
				return text;
			}
			if (displaySegments == 1) {
				String truncated = truncateTextWithSuffix(expression.segments().get(0), suffix, maxUnits, measure);
				if (!truncated.isEmpty()) {
					return truncated + suffix;
				}
			}
			displaySegments -= 1;
		}

		String suffixOnly = SerialDisplayFormatUtil.buildRemainingSuffix(expression.totalSerialCount());
		return measure.applyAsInt(suffixOnly) <= maxUnits ? suffixOnly : EMPTY_TEXT;
	}

	/**
	 * 按给定度量上限构建多行文本。
	 *
	 * @param serials 当前连接目标序号
	 * @param displayTexts 当前连接目标展示文本
	 * @param maxUnits 单行最大度量值
	 * @param maxSegments 最多展示的结构化分段数
	 * @param measure 文本度量器
	 * @return 多行文本
	 */
	public static List<String> buildWrappedLines(
		Collection<Long> serials,
		Collection<String> displayTexts,
		int maxUnits,
		int maxSegments,
		ToIntFunction<String> measure
	) {
		StructuredExpression expression = buildExpression(serials, displayTexts);
		if (expression.isEmpty() || maxUnits <= 0 || maxSegments <= 0 || measure == null) {
			return List.of();
		}

		int displayCount = Math.min(expression.segments().size(), maxSegments);
		List<String> lines = new ArrayList<>();
		StringBuilder currentLine = new StringBuilder();
		for (int index = 0; index < displayCount; index++) {
			String segment = expression.segments().get(index);
			String candidate = currentLine.isEmpty() ? segment : currentLine + SEPARATOR + segment;
			if (measure.applyAsInt(candidate) <= maxUnits) {
				currentLine.setLength(0);
				currentLine.append(candidate);
				continue;
			}
			if (currentLine.length() > 0) {
				lines.add(currentLine.toString());
				currentLine.setLength(0);
			}
			if (measure.applyAsInt(segment) <= maxUnits) {
				currentLine.append(segment);
				continue;
			}
			lines.add(DisplayTextListFormatUtil.truncateText(segment, maxUnits, measure));
		}
		if (currentLine.length() > 0) {
			lines.add(currentLine.toString());
		}

		int remaining = countRemainingSerials(expression, displayCount);
		if (remaining > 0) {
			appendRemainingSuffix(lines, remaining, maxUnits, measure);
		}
		return lines.isEmpty() ? List.of() : List.copyOf(lines);
	}

	/**
	 * 统计从指定分段索引开始被省略的序号总量。
	 */
	public static int countRemainingSerials(StructuredExpression expression, int shownSegments) {
		if (expression == null || expression.isEmpty()) {
			return 0;
		}
		int start = Math.max(0, Math.min(shownSegments, expression.segmentCounts().size()));
		int remaining = 0;
		for (int index = start; index < expression.segmentCounts().size(); index++) {
			remaining += expression.segmentCounts().get(index);
		}
		return remaining;
	}

	private static List<Entry> normalizeEntries(Collection<Long> serials, Collection<String> displayTexts) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<Long> normalizedSerials = new ArrayList<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalizedSerials.add(serial);
			}
		}
		if (normalizedSerials.isEmpty()) {
			return List.of();
		}
		List<String> normalizedDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(normalizedSerials, displayTexts);
		List<Entry> entries = new ArrayList<>(normalizedSerials.size());
		for (int index = 0; index < normalizedSerials.size(); index++) {
			entries.add(new Entry(normalizedSerials.get(index), normalizedDisplayTexts.get(index)));
		}
		return List.copyOf(entries);
	}

	private static void appendRangeSegment(List<String> segments, List<Integer> segmentCounts, long start, long end) {
		if (start <= 0L || end <= 0L) {
			return;
		}
		if (start == end) {
			segments.add(NodeAliasDisplayUtil.formatSerialToken(start));
			segmentCounts.add(1);
			return;
		}
		segments.add(NodeAliasDisplayUtil.formatSerialToken(start) + ":" + NodeAliasDisplayUtil.formatSerialToken(end));
		long count = end - start + 1L;
		segmentCounts.add(count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
	}

	private static void appendRemainingSuffix(
		List<String> lines,
		int remaining,
		int maxUnits,
		ToIntFunction<String> measure
	) {
		String suffix = SerialDisplayFormatUtil.buildRemainingSuffix(remaining);
		if (lines.isEmpty()) {
			lines.add(measure.applyAsInt(suffix) <= maxUnits ? suffix : DisplayTextListFormatUtil.truncateText(suffix, maxUnits, measure));
			return;
		}
		int lastIndex = lines.size() - 1;
		String combined = lines.get(lastIndex) + suffix;
		if (measure.applyAsInt(combined) <= maxUnits) {
			lines.set(lastIndex, combined);
			return;
		}
		if (measure.applyAsInt(suffix) <= maxUnits) {
			lines.add(suffix);
			return;
		}
		lines.add(DisplayTextListFormatUtil.truncateText(suffix, maxUnits, measure));
	}

	private static String truncateTextWithSuffix(
		String text,
		String suffix,
		int maxUnits,
		ToIntFunction<String> measure
	) {
		if (suffix == null || suffix.isEmpty()) {
			String truncated = DisplayTextListFormatUtil.truncateText(text, maxUnits, measure);
			return EMPTY_TEXT.equals(truncated) ? "" : truncated;
		}
		if (measure.applyAsInt(suffix) >= maxUnits || measure.applyAsInt("..." + suffix) > maxUnits) {
			return "";
		}
		String normalizedText = NodeAliasDisplayUtil.normalizeAlias(text);
		int end = normalizedText.length();
		while (end > 0 && measure.applyAsInt(normalizedText.substring(0, end) + "..." + suffix) > maxUnits) {
			end -= 1;
		}
		return end <= 0 ? "" : normalizedText.substring(0, end) + "...";
	}

	/**
	 * 当前连接条目。
	 */
	private record Entry(long serial, String displayText) {
		private static final java.util.regex.Pattern PURE_SERIAL_PATTERN = java.util.regex.Pattern.compile("^#(\\d+)$");

		boolean canJoinRange() {
			String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(displayText);
			java.util.regex.Matcher matcher = PURE_SERIAL_PATTERN.matcher(normalizedDisplayText);
			if (!matcher.matches()) {
				return false;
			}
			try {
				return Long.parseLong(matcher.group(1)) == serial;
			} catch (NumberFormatException exception) {
				return false;
			}
		}
	}

	/**
	 * 当前连接结构化表达式。
	 *
	 * @param segments 展示分段
	 * @param segmentCounts 每个分段代表的序号数量
	 * @param totalSerialCount 总序号数量
	 */
	public record StructuredExpression(List<String> segments, List<Integer> segmentCounts, int totalSerialCount) {
		/**
		 * 空表达式。
		 */
		public static StructuredExpression empty() {
			return new StructuredExpression(List.of(), List.of(), 0);
		}

		/**
		 * @return 是否为空表达式
		 */
		public boolean isEmpty() {
			return segments.isEmpty() || segmentCounts.isEmpty() || totalSerialCount <= 0;
		}

		/**
		 * @return 完整文本
		 */
		public String joinAll() {
			return isEmpty() ? EMPTY_TEXT : String.join(SEPARATOR, segments);
		}
	}
}
