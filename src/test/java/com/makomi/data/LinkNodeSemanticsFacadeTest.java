package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkNodeSemantics 门面方法契约测试。
 */
@Tag("stable-core")
class LinkNodeSemanticsFacadeTest {
	/**
	 * 解析与命名门面应稳定映射到 canonical 语义。
	 */
	@Test
	void parseAndNameFacadeShouldMatchCanonicalSemantics() {
		assertEquals(Optional.of(LinkNodeType.BUTTON), LinkNodeSemantics.tryParseType("button"));
		assertEquals(Optional.of(LinkNodeType.BUTTON), LinkNodeSemantics.tryParseCanonicalType("triggerSource"));
		assertEquals(Optional.of(LinkNodeType.CORE), LinkNodeSemantics.tryParseCanonicalType("core"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseCanonicalType("BUTTON"));
		assertEquals("triggerSource", LinkNodeSemantics.toSemanticName(LinkNodeType.BUTTON));
		assertEquals("core", LinkNodeSemantics.toSemanticName(LinkNodeType.CORE));
	}

	/**
	 * 角色策略与统一解析门面应直接委托语义中转层。
	 */
	@Test
	void roleAndResolveFacadeShouldDelegateToSemanticPolicy() {
		LinkNodeSemantics.resetDefaultRoleRules();
		assertTrue(LinkNodeSemantics.isAllowedForRole(LinkNodeType.BUTTON, LinkNodeSemantics.Role.SOURCE));
		assertFalse(LinkNodeSemantics.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));

		LinkNodeSemantics.registerRoleRule(
			LinkNodeSemantics.Role.SOURCE,
			candidateType -> candidateType == LinkNodeType.CORE
		);
		assertTrue(LinkNodeSemantics.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(LinkNodeSemantics.isAllowedForRole(LinkNodeType.BUTTON, LinkNodeSemantics.Role.SOURCE));
		LinkNodeSemantics.resetDefaultRoleRules();

		assertTrue(
			LinkNodeSemantics.resolveTypeForRole("button", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.BUTTON)).isSuccess()
		);
		assertTrue(
			LinkNodeSemantics
				.resolveCompatibleTypeForRole("trigger_source", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.BUTTON))
				.isSuccess()
		);
		assertTrue(
			LinkNodeSemantics
				.resolveCanonicalTypeForRole("triggerSource", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.BUTTON))
				.isSuccess()
		);
		assertTrue(
			LinkNodeSemantics
				.resolveStrictTypeForRole("triggerSource", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.BUTTON))
				.isSuccess()
		);
	}
}
