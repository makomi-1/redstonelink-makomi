package com.makomi.data;

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
	 * 规范化别名空白。
	 *
	 * @param alias 原始别名
	 * @return 去首尾空白后的别名；空值时返回空串
	 */
	public static String normalizeAlias(String alias) {
		return alias == null ? "" : alias.trim();
	}
}
