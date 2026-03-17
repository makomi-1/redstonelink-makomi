package com.makomi.data;

import com.makomi.data.semantic.SemanticRolePolicy;
import com.makomi.data.semantic.SemanticTypeAliasResolver;
import com.makomi.data.semantic.SemanticResult;
import java.util.Optional;
import java.util.Set;

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
		 * 来源（triggerSource -> TRIGGER_SOURCE）。
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
	 * <p>
	 * 仅接受 triggerSource/core（大小写不敏感）。
	 * </p>
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果，无法识别时返回空
	 */
	public static Optional<LinkNodeType> tryParseType(String rawType) {
		return SemanticTypeAliasResolver.tryResolve(rawType);
	}

	/**
	 * 仅按标准 type 词表解析（triggerSource/core，大小写不敏感）。
	 *
	 * @param rawType 原始类型文本
	 * @return 解析结果；非标准词时返回 empty
	 */
	public static Optional<LinkNodeType> tryParseCanonicalType(String rawType) {
		return SemanticTypeAliasResolver.tryResolveCanonical(rawType);
	}

	/**
	 * 判断类型是否符合语义隔离要求。
	 *
	 * @param type 节点类型
	 * @param role 角色方向
	 * @return 是否允许
	 */
	public static boolean isAllowedForRole(LinkNodeType type, Role role) {
		return SemanticRolePolicy.isAllowedForRole(type, role);
	}

	/**
	 * 注册角色语义校验规则。
	 *
	 * @param role 角色方向
	 * @param rule 校验规则
	 */
	public static void registerRoleRule(Role role, SemanticRolePolicy.RoleTypeRule rule) {
		SemanticRolePolicy.registerRoleRule(role, rule);
	}

	/**
	 * 重置为默认角色语义规则。
	 */
	public static void resetDefaultRoleRules() {
		SemanticRolePolicy.resetDefaultRoleRules();
	}

	/**
	 * 语义化输出类型名称。
	 *
	 * @param type 节点类型
	 * @return 语义化名称
	 */
	public static String toSemanticName(LinkNodeType type) {
		return SemanticTypeAliasResolver.toSemanticName(type);
	}

	/**
	 * 将节点类型转换为命令侧使用的语义 token。
	 * <p>
	 * 该 token 统一使用小写且无分隔符，避免 GUI 端散落硬编码字符串。
	 * </p>
	 *
	 * @param type 节点类型
	 * @return 命令 token；未知类型返回 "unknown"
	 */
	public static String toCommandToken(LinkNodeType type) {
		if (type == null) {
			return "unknown";
		}
		return switch (type) {
			case TRIGGER_SOURCE -> "triggersource";
			case CORE -> "core";
		};
	}

	/**
	 * 根据来源类型推导目标类型，统一 triggerSource/core 方向映射。
	 *
	 * @param sourceType 来源节点类型
	 * @return 目标节点类型；未知类型时返回 null
	 */
	public static LinkNodeType resolveTargetTypeForSource(LinkNodeType sourceType) {
		if (sourceType == LinkNodeType.TRIGGER_SOURCE) {
			return LinkNodeType.CORE;
		}
		if (sourceType == LinkNodeType.CORE) {
			return LinkNodeType.TRIGGER_SOURCE;
		}
		return null;
	}

	/**
	 * 解析并校验类型是否满足角色与可选配置允许集。
	 *
	 * @param rawType 原始类型文本
	 * @param role 角色方向
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 统一语义校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveTypeForRole(
		String rawType,
		Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return resolveCompatibleTypeForRole(rawType, role, allowedTypes);
	}

	/**
	 * 仅按标准 type 词表解析并校验是否满足角色与可选配置允许集。
	 *
	 * @param rawType 原始类型文本（仅接受 triggerSource/core，大小写不敏感）
	 * @param role 角色方向
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 统一语义校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveCanonicalTypeForRole(
		String rawType,
		Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return resolveStrictTypeForRole(rawType, role, allowedTypes);
	}

	/**
	 * 按 canonical 词表解析并校验是否满足角色与可选配置允许集。
	 * <p>
	 * 该入口名保留为兼容命名，但行为与 strict 入口一致。
	 * </p>
	 *
	 * @param rawType 原始类型文本
	 * @param role 角色方向
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 统一语义校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveCompatibleTypeForRole(
		String rawType,
		Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return SemanticRolePolicy.resolveCompatibleTypeForRole(rawType, role, allowedTypes);
	}

	/**
	 * 按严格词表（仅 triggerSource/core）解析并校验是否满足角色与可选配置允许集。
	 *
	 * @param rawType 原始类型文本
	 * @param role 角色方向
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 统一语义校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveStrictTypeForRole(
		String rawType,
		Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return SemanticRolePolicy.resolveStrictTypeForRole(rawType, role, allowedTypes);
	}
}
