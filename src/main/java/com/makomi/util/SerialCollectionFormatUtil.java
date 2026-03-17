package com.makomi.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 序号集合格式化与归一化工具。
 * <p>
 * 提供命令反馈使用的稳定升序 CSV 格式，以及“正数+去重+排序”的基础归一化能力。
 * </p>
 */
public final class SerialCollectionFormatUtil {
	private static final String EMPTY_TEXT = "-";

	private SerialCollectionFormatUtil() {
	}

	/**
	 * 将序号集合格式化为稳定升序的逗号分隔文本。
	 *
	 * @param serials 序号集合
	 * @return 文本；空集合返回 "-"
	 */
	public static String formatSortedCsv(Collection<Long> serials) {
		return formatSortedCsvLimited(serials, Integer.MAX_VALUE, Integer.MAX_VALUE);
	}

	/**
	 * 将序号集合格式化为稳定升序文本，并限制输出条目与字符长度。
	 * <p>
	 * 当发生截断时，尾部追加 ", ... (+N more)"。
	 * </p>
	 *
	 * @param serials 序号集合
	 * @param maxItems 最大输出条目数（<=0 视为空输出）
	 * @param maxChars 最大输出字符数（<=0 视为空输出）
	 * @return 格式化文本；无法输出时返回 "-"
	 */
	public static String formatSortedCsvLimited(Collection<Long> serials, int maxItems, int maxChars) {
		if (serials == null || serials.isEmpty() || maxItems <= 0 || maxChars <= 0) {
			return EMPTY_TEXT;
		}

		List<Long> sortedSerials = new ArrayList<>(serials.size());
		for (Long serial : serials) {
			if (serial != null) {
				sortedSerials.add(serial);
			}
		}
		if (sortedSerials.isEmpty()) {
			return EMPTY_TEXT;
		}
		sortedSerials.sort(Long::compareTo);

		StringBuilder builder = new StringBuilder();
		int shownCount = 0;
		for (Long serial : sortedSerials) {
			if (shownCount >= maxItems) {
				break;
			}
			String token = String.valueOf(serial);
			String separator = shownCount == 0 ? "" : ", ";
			int appendedLength = builder.length() + separator.length() + token.length();
			if (appendedLength > maxChars) {
				break;
			}
			builder.append(separator).append(token);
			shownCount++;
		}

		if (shownCount <= 0) {
			return EMPTY_TEXT;
		}
		int omittedCount = sortedSerials.size() - shownCount;
		if (omittedCount > 0) {
			builder.append(", ... (+").append(omittedCount).append(" more)");
		}
		return builder.toString();
	}

	/**
	 * 将输入序号归一化为“正数+去重+升序”列表。
	 *
	 * @param serials 输入集合
	 * @return 不可变列表；无有效数据返回空列表
	 */
	public static List<Long> normalizePositiveDistinctSorted(Collection<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<Long> values = new ArrayList<>(serials.size());
		for (Long value : serials) {
			if (value != null && value > 0L) {
				values.add(value);
			}
		}
		if (values.isEmpty()) {
			return List.of();
		}
		values.sort(Long::compareTo);

		List<Long> deduplicated = new ArrayList<>(values.size());
		long previous = Long.MIN_VALUE;
		for (long value : values) {
			if (value == previous) {
				continue;
			}
			deduplicated.add(value);
			previous = value;
		}
		return deduplicated.isEmpty() ? List.of() : List.copyOf(deduplicated);
	}
}
