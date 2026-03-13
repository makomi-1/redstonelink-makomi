package com.makomi.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * 序号结构化展示工具。
 * <p>
 * 将序号集合按升序压缩为 `N` / `A:B` 分段，并支持按长度截断后追加 `(+n)` 提示。
 * 仅用于展示，不参与存储与解析。
 * </p>
 */
public final class SerialDisplayFormatUtil {
	private static final String EMPTY_TEXT = "-";

	private SerialDisplayFormatUtil() {
	}

	/**
	 * 构建序号结构化表达式。
	 *
	 * @param serials 输入序号集合（会自动过滤非正数并去重）
	 * @return 结构化表达式
	 */
	public static StructuredExpression buildExpression(Collection<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return StructuredExpression.empty();
		}

		TreeSet<Long> normalized = new TreeSet<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		if (normalized.isEmpty()) {
			return StructuredExpression.empty();
		}

		List<String> segments = new ArrayList<>();
		List<Integer> segmentCounts = new ArrayList<>();
		Long rangeStart = null;
		Long previous = null;
		for (Long serial : normalized) {
			if (rangeStart == null) {
				rangeStart = serial;
				previous = serial;
				continue;
			}
			if (serial == previous + 1) {
				previous = serial;
				continue;
			}
			appendSegment(segments, segmentCounts, rangeStart, previous);
			rangeStart = serial;
			previous = serial;
		}
		if (rangeStart != null && previous != null) {
			appendSegment(segments, segmentCounts, rangeStart, previous);
		}

		return new StructuredExpression(List.copyOf(segments), List.copyOf(segmentCounts), normalized.size());
	}

	/**
	 * 构建带字符上限的结构化展示文本。
	 *
	 * @param serials 输入序号集合
	 * @param maxChars 最大字符数
	 * @return 展示文本（空集合返回 `-`）
	 */
	public static String buildText(Collection<Long> serials, int maxChars) {
		StructuredExpression expression = buildExpression(serials);
		return buildText(expression, maxChars, expression.segments().size());
	}

	/**
	 * 构建带字符上限与分段数量上限的结构化展示文本。
	 *
	 * @param expression 结构化表达式
	 * @param maxChars 最大字符数
	 * @param maxSegments 最大分段数（<=0 表示不显示分段）
	 * @return 展示文本
	 */
	public static String buildText(StructuredExpression expression, int maxChars, int maxSegments) {
		if (expression == null || expression.isEmpty() || maxChars <= 0 || maxSegments <= 0) {
			return EMPTY_TEXT;
		}

		int displaySegments = Math.min(expression.segments().size(), maxSegments);
		while (displaySegments > 0) {
			String base = String.join("/", expression.segments().subList(0, displaySegments));
			int omitted = countRemainingSerials(expression, displaySegments);
			String text = omitted > 0 ? base + buildRemainingSuffix(omitted) : base;
			if (text.length() <= maxChars) {
				return text;
			}
			displaySegments -= 1;
		}

		int omittedAll = countRemainingSerials(expression, 0);
		String suffixOnly = buildRemainingSuffix(omittedAll);
		return suffixOnly.length() <= maxChars ? suffixOnly : EMPTY_TEXT;
	}

	/**
	 * 统计从指定分段索引开始被省略的序号总量。
	 *
	 * @param expression 结构化表达式
	 * @param shownSegments 已显示分段数量
	 * @return 被省略的序号数量
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

	/**
	 * 构建剩余数量提示，格式为 `(+n)`。
	 *
	 * @param remaining 省略数量
	 * @return 提示文本
	 */
	public static String buildRemainingSuffix(int remaining) {
		return "(+" + Math.max(0, remaining) + ")";
	}

	/**
	 * 追加单个分段信息。
	 */
	private static void appendSegment(List<String> segments, List<Integer> segmentCounts, long start, long end) {
		if (start == end) {
			segments.add(Long.toString(start));
			segmentCounts.add(1);
			return;
		}
		segments.add(start + ":" + end);
		long count = end - start + 1;
		if (count > Integer.MAX_VALUE) {
			segmentCounts.add(Integer.MAX_VALUE);
		} else {
			segmentCounts.add((int) count);
		}
	}

	/**
	 * 结构化表达式。
	 *
	 * @param segments 展示分段（`N` / `A:B`）
	 * @param segmentCounts 每个分段对应的序号数量
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
		 * @return 不截断的完整结构化文本
		 */
		public String joinAll() {
			return isEmpty() ? EMPTY_TEXT : String.join("/", segments);
		}
	}
}
