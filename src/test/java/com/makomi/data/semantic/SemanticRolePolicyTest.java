package com.makomi.data.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 语义中转内核最小契约测试。
 */
@Tag("stable-core")
class SemanticRolePolicyTest {
	/**
	 * 类型解析应仅接受 canonical 词表。
	 */
	@Test
	void typeResolverShouldOnlyAcceptCanonicalTokens() {
		assertEquals(Optional.of(LinkNodeType.TRIGGER_SOURCE), SemanticTypeAliasResolver.tryResolve("triggersource"));
		assertEquals(Optional.empty(), SemanticTypeAliasResolver.tryResolve("trigger_source"));
		assertEquals(Optional.empty(), SemanticTypeAliasResolver.tryResolve("button"));
		assertEquals(Optional.of(LinkNodeType.CORE), SemanticTypeAliasResolver.tryResolve("core"));
		assertEquals(Optional.empty(), SemanticTypeAliasResolver.tryResolve("unknown_type"));
	}

	/**
	 * 命令层标准 type 解析仅接受 triggerSource/core（大小写不敏感）。
	 */
	@Test
	void canonicalTypeResolverShouldOnlyAcceptCanonicalTokens() {
		assertEquals(Optional.of(LinkNodeType.TRIGGER_SOURCE), LinkNodeSemantics.tryParseCanonicalType("triggerSource"));
		assertEquals(Optional.of(LinkNodeType.TRIGGER_SOURCE), LinkNodeSemantics.tryParseCanonicalType("TRIGGERSOURCE"));
		assertEquals(Optional.of(LinkNodeType.CORE), LinkNodeSemantics.tryParseCanonicalType("core"));
		assertEquals(Optional.of(LinkNodeType.CORE), LinkNodeSemantics.tryParseCanonicalType("CORE"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseCanonicalType("button"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseCanonicalType("trigger_source"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseCanonicalType("targets"));
	}

	/**
	 * 命令 token 映射应稳定输出 triggerSource/core 语义，并对空值做兜底。
	 */
	@Test
	void commandTokenShouldMatchSemanticNames() {
		assertEquals("triggersource", LinkNodeSemantics.toCommandToken(LinkNodeType.TRIGGER_SOURCE));
		assertEquals("core", LinkNodeSemantics.toCommandToken(LinkNodeType.CORE));
		assertEquals("unknown", LinkNodeSemantics.toCommandToken(null));
	}

	/**
	 * 角色语义隔离应保持 SOURCE=TRIGGER_SOURCE、TARGET=CORE。
	 */
	@Test
	void rolePolicyShouldEnforceSemanticIsolation() {
		SemanticRolePolicy.resetDefaultRoleRules();
		assertTrue(SemanticRolePolicy.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(SemanticRolePolicy.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));
		assertTrue(SemanticRolePolicy.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.TARGET));
		assertFalse(SemanticRolePolicy.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.TARGET));
	}

	/**
	 * 角色规则注册后应立即生效，并支持恢复默认策略。
	 */
	@Test
	void rolePolicyShouldSupportRegisterAndReset() {
		SemanticRolePolicy.resetDefaultRoleRules();
		SemanticRolePolicy.registerRoleRule(
			LinkNodeSemantics.Role.SOURCE,
			candidateType -> candidateType == LinkNodeType.CORE
		);
		assertTrue(SemanticRolePolicy.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(SemanticRolePolicy.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.SOURCE));

		SemanticRolePolicy.resetDefaultRoleRules();
		assertTrue(SemanticRolePolicy.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(SemanticRolePolicy.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));
	}

	/**
	 * 统一校验应覆盖类型解析、角色约束与配置允许集。
	 */
	@Test
	void resolveTypeShouldValidateTypeRoleAndAllowedSet() {
		SemanticRolePolicy.resetDefaultRoleRules();
		SemanticResult<LinkNodeType> success = SemanticRolePolicy.resolveType(
			"triggerSource",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertTrue(success.isSuccess());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, success.value());
		assertEquals(SemanticError.NONE, success.error());

		SemanticResult<LinkNodeType> roleNotAllowed = SemanticRolePolicy.resolveType(
			"core",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.CORE)
		);
		assertFalse(roleNotAllowed.isSuccess());
		assertEquals(SemanticError.ROLE_NOT_ALLOWED, roleNotAllowed.error());

		SemanticResult<LinkNodeType> configNotAllowed = SemanticRolePolicy.resolveType(
			"triggerSource",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.CORE)
		);
		assertFalse(configNotAllowed.isSuccess());
		assertEquals(SemanticError.CONFIG_NOT_ALLOWED, configNotAllowed.error());

		SemanticResult<LinkNodeType> invalidType = SemanticRolePolicy.resolveType(
			"not_exists",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertFalse(invalidType.isSuccess());
		assertEquals(SemanticError.INVALID_TYPE, invalidType.error());
	}

	/**
	 * strict 校验应仅接受 canonical type，并继续覆盖角色与允许集约束。
	 */
	@Test
	void resolveCanonicalTypeShouldValidateTypeRoleAndAllowedSet() {
		SemanticRolePolicy.resetDefaultRoleRules();
		SemanticResult<LinkNodeType> success = SemanticRolePolicy.resolveCanonicalType(
			"TRIGGERSOURCE",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertTrue(success.isSuccess());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, success.value());
		assertEquals(SemanticError.NONE, success.error());

		SemanticResult<LinkNodeType> roleNotAllowed = SemanticRolePolicy.resolveCanonicalType(
			"core",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.CORE)
		);
		assertFalse(roleNotAllowed.isSuccess());
		assertEquals(SemanticError.ROLE_NOT_ALLOWED, roleNotAllowed.error());

		SemanticResult<LinkNodeType> configNotAllowed = SemanticRolePolicy.resolveCanonicalType(
			"triggerSource",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.CORE)
		);
		assertFalse(configNotAllowed.isSuccess());
		assertEquals(SemanticError.CONFIG_NOT_ALLOWED, configNotAllowed.error());

		SemanticResult<LinkNodeType> invalidAliasType = SemanticRolePolicy.resolveCanonicalType(
			"button",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertFalse(invalidAliasType.isSuccess());
		assertEquals(SemanticError.INVALID_TYPE, invalidAliasType.error());
	}

	/**
	 * 新旧命名入口应保持等价行为（仅命名调整，不改变解析语义）。
	 */
	@Test
	void namingAliasApisShouldKeepBehaviorEquivalent() {
		SemanticRolePolicy.resetDefaultRoleRules();
		SemanticResult<LinkNodeType> legacyCompatible = LinkNodeSemantics.resolveTypeForRole(
			"triggerSource",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		SemanticResult<LinkNodeType> newCompatible = LinkNodeSemantics.resolveCompatibleTypeForRole(
			"triggerSource",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertEquals(legacyCompatible.isSuccess(), newCompatible.isSuccess());
		assertEquals(legacyCompatible.error(), newCompatible.error());
		assertEquals(legacyCompatible.value(), newCompatible.value());

		SemanticResult<LinkNodeType> legacyStrict = LinkNodeSemantics.resolveCanonicalTypeForRole(
			"TRIGGERSOURCE",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		SemanticResult<LinkNodeType> newStrict = LinkNodeSemantics.resolveStrictTypeForRole(
			"TRIGGERSOURCE",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertEquals(legacyStrict.isSuccess(), newStrict.isSuccess());
		assertEquals(legacyStrict.error(), newStrict.error());
		assertEquals(legacyStrict.value(), newStrict.value());

		SemanticResult<LinkNodeType> legacyInvalid = LinkNodeSemantics.resolveTypeForRole(
			"button",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		SemanticResult<LinkNodeType> newInvalid = LinkNodeSemantics.resolveCompatibleTypeForRole(
			"button",
			LinkNodeSemantics.Role.SOURCE,
			Set.of(LinkNodeType.TRIGGER_SOURCE)
		);
		assertFalse(legacyInvalid.isSuccess());
		assertFalse(newInvalid.isSuccess());
		assertEquals(legacyInvalid.error(), newInvalid.error());
	}
}
