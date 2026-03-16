package com.makomi.data.semantic;

import com.makomi.data.LinkNodeType;
import java.util.Optional;

/**
 * 节点类型语义解析器。
 */
public final class SemanticTypeAliasResolver {
	private SemanticTypeAliasResolver() {
	}

	/**
	 * 按 canonical 语义词表解析节点类型。
	 * <p>
	 * 仅接受 triggerSource/core（大小写不敏感），不再接受历史别名。
	 * </p>
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果；无法识别时返回空
	 */
	public static Optional<LinkNodeType> tryResolve(String rawType) {
		return tryResolveCanonical(rawType);
	}

	/**
	 * 仅按标准 type 词表解析（triggerSource/core，大小写不敏感）。
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果；非标准词时返回 empty
	 */
	public static Optional<LinkNodeType> tryResolveCanonical(String rawType) {
		if (rawType == null) {
			return Optional.empty();
		}
		String normalized = rawType.trim();
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		if ("triggerSource".equalsIgnoreCase(normalized)) {
			return Optional.of(LinkNodeType.TRIGGER_SOURCE);
		}
		if ("core".equalsIgnoreCase(normalized)) {
			return Optional.of(LinkNodeType.CORE);
		}
		return Optional.empty();
	}

	/**
	 * 语义化输出节点类型名称。
	 *
	 * @param type 节点类型
	 * @return 语义化名称
	 */
	public static String toSemanticName(LinkNodeType type) {
		if (type == null) {
			return "unknown";
		}
		return type == LinkNodeType.TRIGGER_SOURCE ? "triggerSource" : "core";
	}
}
