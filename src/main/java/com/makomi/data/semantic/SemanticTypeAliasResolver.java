package com.makomi.data.semantic;

import com.makomi.data.LinkNodeType;
import java.util.Locale;
import java.util.Optional;

/**
 * 节点类型语义别名解析器。
 */
public final class SemanticTypeAliasResolver {
	private SemanticTypeAliasResolver() {
	}

	/**
	 * 按语义别名解析节点类型。
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果；无法识别时返回空
	 */
	public static Optional<LinkNodeType> tryResolve(String rawType) {
		if (rawType == null) {
			return Optional.empty();
		}
		String normalized = rawType.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		switch (normalized) {
			case "triggersource", "trigger_source", "trigger-source", "button":
				return Optional.of(LinkNodeType.BUTTON);
			case "core":
				return Optional.of(LinkNodeType.CORE);
			default:
				for (LinkNodeType value : LinkNodeType.values()) {
					if (value.name().equalsIgnoreCase(normalized)) {
						return Optional.of(value);
					}
				}
				return Optional.empty();
		}
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
			return Optional.of(LinkNodeType.BUTTON);
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
		return type == LinkNodeType.BUTTON ? "triggerSource" : "core";
	}
}
