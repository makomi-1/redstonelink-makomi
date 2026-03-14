package com.makomi.data.semantic;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 节点类型与角色语义策略。
 * <p>
 * 该类同时提供：
 * </p>
 * <ul>
 *   <li>默认语义隔离规则（triggerSource=BUTTON、core=CORE）；</li>
 *   <li>可注册的角色校验规则，便于后续扩展新语义。</li>
 * </ul>
 */
public final class SemanticRolePolicy {
	private static final Map<LinkNodeSemantics.Role, RoleTypeRule> ROLE_RULES = new EnumMap<>(LinkNodeSemantics.Role.class);

	static {
		resetDefaultRoleRules();
	}

	private SemanticRolePolicy() {
	}

	/**
	 * 角色类型校验函数。
	 */
	@FunctionalInterface
	public interface RoleTypeRule {
		/**
		 * 判断候选类型是否允许用于指定角色。
		 *
		 * @param candidateType 候选节点类型
		 * @return 是否允许
		 */
		boolean isAllowed(LinkNodeType candidateType);
	}

	/**
	 * 注册角色校验规则。
	 *
	 * @param role 角色
	 * @param rule 校验规则
	 */
	public static synchronized void registerRoleRule(LinkNodeSemantics.Role role, RoleTypeRule rule) {
		ROLE_RULES.put(
			Objects.requireNonNull(role, "role"),
			Objects.requireNonNull(rule, "rule")
		);
	}

	/**
	 * 重置为默认语义隔离规则。
	 */
	public static synchronized void resetDefaultRoleRules() {
		ROLE_RULES.clear();
		ROLE_RULES.put(LinkNodeSemantics.Role.SOURCE, candidateType -> candidateType == LinkNodeType.BUTTON);
		ROLE_RULES.put(LinkNodeSemantics.Role.TARGET, candidateType -> candidateType == LinkNodeType.CORE);
	}

	/**
	 * 判断类型是否符合角色语义隔离要求。
	 *
	 * @param type 节点类型
	 * @param role 角色
	 * @return 是否允许
	 */
	public static boolean isAllowedForRole(LinkNodeType type, LinkNodeSemantics.Role role) {
		if (type == null || role == null) {
			return false;
		}
		RoleTypeRule rule;
		synchronized (SemanticRolePolicy.class) {
			rule = ROLE_RULES.get(role);
		}
		return rule != null && rule.isAllowed(type);
	}

	/**
	 * 解析并校验类型是否满足角色与配置允许集。
	 *
	 * @param rawType 原始类型文本
	 * @param role 角色
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveType(
		String rawType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return validateParsedType(SemanticTypeAliasResolver.tryResolve(rawType), role, allowedTypes);
	}

	/**
	 * 仅按标准 type 词表解析并校验类型是否满足角色与配置允许集。
	 *
	 * @param rawType 原始类型文本（仅接受 triggerSource/core，大小写不敏感）
	 * @param role 语义角色
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 校验结果
	 */
	public static SemanticResult<LinkNodeType> resolveCanonicalType(
		String rawType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		return validateParsedType(SemanticTypeAliasResolver.tryResolveCanonical(rawType), role, allowedTypes);
	}

	/**
	 * 基于已解析类型执行角色与配置允许集校验。
	 *
	 * @param parsedType 类型解析结果
	 * @param role 语义角色
	 * @param allowedTypes 配置允许集（null 表示不做该层校验）
	 * @return 校验结果
	 */
	private static SemanticResult<LinkNodeType> validateParsedType(
		java.util.Optional<LinkNodeType> parsedType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		if (parsedType.isEmpty()) {
			return SemanticResult.failure(SemanticError.INVALID_TYPE);
		}
		LinkNodeType type = parsedType.get();
		if (!isAllowedForRole(type, role)) {
			return SemanticResult.failure(SemanticError.ROLE_NOT_ALLOWED);
		}
		if (allowedTypes != null && !allowedTypes.contains(type)) {
			return SemanticResult.failure(SemanticError.CONFIG_NOT_ALLOWED);
		}
		return SemanticResult.success(type);
	}
}
