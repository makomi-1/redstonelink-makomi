package com.makomi.command.semantic;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.semantic.SemanticError;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * 命令侧语义错误消息适配器。
 * <p>
 * 统一将语义内核返回的错误类型映射为用户可读提示，避免各命令入口重复拼装文案。
 * </p>
 */
public final class SemanticCommandMessageAdapter {
	private SemanticCommandMessageAdapter() {
	}

	/**
	 * 构建“类型解析/校验失败”提示。
	 *
	 * @param error 语义错误类型
	 * @param rawType 原始类型文本
	 * @param role 角色方向
	 * @param allowedTypes 当前配置允许的类型集合
	 * @return 命令反馈文案
	 */
	public static Component resolveTypeFailure(
		SemanticError error,
		String rawType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		if (error == SemanticError.ROLE_NOT_ALLOWED) {
			return Component.translatable(
				"message.redstonelink.crosschunk.invalid_type_for_role",
				rawType,
				roleName(role)
			);
		}
		if (error == SemanticError.CONFIG_NOT_ALLOWED) {
			return Component.translatable(
				"message.redstonelink.crosschunk.type_not_allowed",
				rawType,
				roleName(role),
				formatAllowedTypes(allowedTypes)
			);
		}
		return invalidType(rawType);
	}

	/**
	 * 构建“类型无效”提示。
	 *
	 * @param rawType 原始类型文本
	 * @return 命令反馈文案
	 */
	public static Component invalidType(String rawType) {
		return Component.translatable("message.redstonelink.node.invalid_type", rawType);
	}

	/**
	 * 格式化可用类型集合。
	 *
	 * @param allowedTypes 可用类型集合
	 * @return 逗号分隔文本
	 */
	private static String formatAllowedTypes(Set<LinkNodeType> allowedTypes) {
		if (allowedTypes == null || allowedTypes.isEmpty()) {
			return "-";
		}
		return allowedTypes.stream()
			.map(LinkNodeSemantics::toSemanticName)
			.sorted()
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}

	/**
	 * 语义角色展示名称。
	 *
	 * @param role 角色方向
	 * @return 展示名
	 */
	private static String roleName(LinkNodeSemantics.Role role) {
		return role == LinkNodeSemantics.Role.SOURCE ? "source" : "target";
	}
}
