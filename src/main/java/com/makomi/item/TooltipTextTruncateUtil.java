package com.makomi.item;

import com.makomi.util.SerialDisplayFormatUtil;
import java.util.List;

/**
 * 物品 tooltip 序号文本构建工具。
 * <p>
 * 统一使用结构化表达式（`N` / `A:B` + `/`）展示，并按字符上限截断。
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
	 * 根据最大字符数构建结构化目标列表文本，超出时追加 `(+n)`。
	 *
	 * @param targets 目标序号列表
	 * @param maxChars 最大允许字符数
	 * @return 处理后的文本，空列表返回 `-`
	 */
	public static String buildTargetsText(List<Long> targets, int maxChars) {
		return SerialDisplayFormatUtil.buildText(targets, maxChars);
	}
}
