package com.makomi.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 节点别名展示格式工具。
 * <p>
 * 统一把“别名 + 序号”渲染为稳定的 `别名(#序号)` 形式；
 * 无别名时回退为 `#序号`，无有效序号时回退为 `-`。
 * </p>
 */
public final class NodeAliasDisplayUtil {
	private NodeAliasDisplayUtil() {
	}

	/**
	 * 格式化仅含序号的展示文本。
	 *
	 * @param serial 节点序号
	 * @return `#序号` 或 `-`
	 */
	public static String formatSerialToken(long serial) {
		return serial > 0L ? "#" + serial : "-";
	}

	/**
	 * 将别名与序号组合为统一展示文本。
	 *
	 * @param alias 节点别名
	 * @param serial 节点序号
	 * @return `别名(#序号)`、`#序号` 或 `-`
	 */
	public static String formatDisplayText(String alias, long serial) {
		String serialToken = formatSerialToken(serial);
		if (serial <= 0L) {
			return serialToken;
		}
		String normalizedAlias = normalizeAlias(alias);
		if (normalizedAlias.isEmpty()) {
			return serialToken;
		}
		return normalizedAlias + "(" + serialToken + ")";
	}

	/**
	 * 规范化单个展示文本；为空时按序号兜底。
	 *
	 * @param displayText 原始展示文本
	 * @param serial 对应节点序号
	 * @return 规范化后的展示文本
	 */
	public static String normalizeDisplayText(String displayText, long serial) {
		String normalizedDisplayText = normalizeAlias(displayText);
		return normalizedDisplayText.isEmpty() ? formatDisplayText("", serial) : normalizedDisplayText;
	}

	/**
	 * 规范化展示文本列表，并与给定序号列表按索引对齐。
	 * <p>
	 * 当展示文本数量与序号数量不一致时，会统一按序号兜底，避免出现错位展示。
	 * </p>
	 *
	 * @param serials 序号列表
	 * @param displayTexts 展示文本列表
	 * @return 与序号列表一一对应的展示文本快照
	 */
	public static List<String> normalizeDisplayTexts(Collection<Long> serials, Collection<String> displayTexts) {
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

		List<String> providedDisplayTexts = new ArrayList<>();
		if (displayTexts != null) {
			for (String displayText : displayTexts) {
				providedDisplayTexts.add(displayText);
			}
		}
		boolean aligned = providedDisplayTexts.size() == normalizedSerials.size();
		List<String> normalizedDisplayTexts = new ArrayList<>(normalizedSerials.size());
		for (int index = 0; index < normalizedSerials.size(); index++) {
			long serial = normalizedSerials.get(index);
			String displayText = aligned ? providedDisplayTexts.get(index) : "";
			normalizedDisplayTexts.add(normalizeDisplayText(displayText, serial));
		}
		return List.copyOf(normalizedDisplayTexts);
	}

	/**
	 * 规范化别名空白。
	 *
	 * @param alias 原始别名
	 * @return 去首尾空白后的别名；空值时返回空串
	 */
	public static String normalizeAlias(String alias) {
		return alias == null ? "" : alias.trim();
	}
}
