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
		assertEquals(Optional.of(LinkNodeType.TRIGGER_SOURCE), LinkNodeSemantics.tryParseType("triggerSource"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseType("button"));
		assertEquals(Optional.of(LinkNodeType.TRIGGER_SOURCE), LinkNodeSemantics.tryParseCanonicalType("triggerSource"));
		assertEquals(Optional.of(LinkNodeType.CORE), LinkNodeSemantics.tryParseCanonicalType("core"));
		assertEquals(Optional.empty(), LinkNodeSemantics.tryParseCanonicalType("TRIGGER_SOURCE"));
		assertEquals("triggerSource", LinkNodeSemantics.toSemanticName(LinkNodeType.TRIGGER_SOURCE));
		assertEquals("core", LinkNodeSemantics.toSemanticName(LinkNodeType.CORE));
	}

	/**
	 * 角色策略与统一解析门面应直接委托语义中转层。
	 */
	@Test
	void roleAndResolveFacadeShouldDelegateToSemanticPolicy() {
		LinkNodeSemantics.resetDefaultRoleRules();
		assertTrue(LinkNodeSemantics.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(LinkNodeSemantics.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));

		LinkNodeSemantics.registerRoleRule(
			LinkNodeSemantics.Role.SOURCE,
			candidateType -> candidateType == LinkNodeType.CORE
		);
		assertTrue(LinkNodeSemantics.isAllowedForRole(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE));
		assertFalse(LinkNodeSemantics.isAllowedForRole(LinkNodeType.TRIGGER_SOURCE, LinkNodeSemantics.Role.SOURCE));
		LinkNodeSemantics.resetDefaultRoleRules();

		assertTrue(
			LinkNodeSemantics
				.resolveTypeForRole("triggerSource", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.TRIGGER_SOURCE))
				.isSuccess()
		);
		assertFalse(
			LinkNodeSemantics.resolveTypeForRole("button", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.TRIGGER_SOURCE)).isSuccess()
		);
		assertFalse(
			LinkNodeSemantics
				.resolveCompatibleTypeForRole("trigger_source", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.TRIGGER_SOURCE))
				.isSuccess()
		);
		assertTrue(
			LinkNodeSemantics
				.resolveCanonicalTypeForRole("triggerSource", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.TRIGGER_SOURCE))
				.isSuccess()
		);
		assertTrue(
			LinkNodeSemantics
				.resolveStrictTypeForRole("triggerSource", LinkNodeSemantics.Role.SOURCE, Set.of(LinkNodeType.TRIGGER_SOURCE))
				.isSuccess()
		);
	}
}
