package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * LinkNode 语义工具类。
 * <p>
 * 统一 triggerSource/core 语义映射，并提供类型解析与语义隔离校验。
 * </p>
 */
public final class LinkNodeSemantics {
	/**
	 * 白名单与调度中的角色方向。
	 */
	public enum Role {
		/**
		 * 来源（triggerSource -> BUTTON）。
		 */
		SOURCE,
		/**
		 * 目标（core -> CORE）。
		 */
		TARGET
	}

	private LinkNodeSemantics() {
	}

	/**
	 * 解析配置或命令中的节点类型文本。
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果，无法识别时返回空
	 */
	public static Optional<LinkNodeType> tryParseType(String rawType) {
		if (rawType == null) {
			return Optional.empty();
		}
		String normalized = rawType.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		switch (normalized) {
			case "triggersource", "trigger_source", "trigger-source", "button" -> {
				return Optional.of(LinkNodeType.BUTTON);
			}
			case "core" -> {
				return Optional.of(LinkNodeType.CORE);
			}
			default -> {
				for (LinkNodeType value : LinkNodeType.values()) {
					if (value.name().equalsIgnoreCase(normalized)) {
						return Optional.of(value);
					}
				}
				return Optional.empty();
			}
		}
	}

	/**
	 * 判断类型是否符合语义隔离要求。
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @return 是否允许
	 */
	public static boolean isAllowedForRole(LinkNodeType type, Role role) {
		if (type == null || role == null) {
			return false;
		}
		return switch (role) {
			case SOURCE -> type == LinkNodeType.BUTTON;
			case TARGET -> type == LinkNodeType.CORE;
		};
	}

	/**
	 * 语义化输出类型名称。
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
