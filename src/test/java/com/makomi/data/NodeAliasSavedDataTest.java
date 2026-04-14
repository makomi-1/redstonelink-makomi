package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 节点别名数据层纯逻辑测试。
 */
@Tag("stable-core")
class NodeAliasSavedDataTest {
	@Test
	void upsertShouldResolveAliasWithinTypeAndAcrossTypes() {
		NodeAliasSavedData data = new NodeAliasSavedData();

		assertTrue(data.upsert(LinkNodeType.TRIGGER_SOURCE, 12L, "大门1").changed());
		assertTrue(data.upsert(LinkNodeType.CORE, 3L, "中控").changed());

		assertEquals(12L, data.resolveSerial(LinkNodeType.TRIGGER_SOURCE, "大门1").orElseThrow());
		assertEquals(2, data.listAll().size());

		List<NodeAliasSavedData.Entry> resolved = data.resolveAllByAlias("大门1");
		assertEquals(1, resolved.size());
		assertEquals(LinkNodeType.TRIGGER_SOURCE, resolved.get(0).nodeType());
		assertEquals(12L, resolved.get(0).serial());
	}

	@Test
	void validateAliasShouldRejectNumericOnlyAndInvalidCharacters() {
		assertFalse(NodeAliasSavedData.validateAlias("123").valid());
		assertFalse(NodeAliasSavedData.validateAlias("门-1").valid());
		assertTrue(NodeAliasSavedData.validateAlias("门1").valid());
		assertEquals(NodeAliasDisplayUtil.normalizeAlias(" 门1 "), NodeAliasSavedData.validateAlias(" 门1 ").normalizedAlias());
	}

	@Test
	void removeShouldClearAliasResolution() {
		NodeAliasSavedData data = new NodeAliasSavedData();
		assertTrue(data.upsert(LinkNodeType.CORE, 9L, "中控A").changed());

		NodeAliasSavedData.RemoveResult result = data.remove(LinkNodeType.CORE, 9L);
		assertTrue(result.removed());
		assertEquals("中控A", result.alias());
		assertTrue(data.getAlias(LinkNodeType.CORE, 9L).isEmpty());
		assertTrue(data.resolveAllByAlias("中控A").isEmpty());
	}
}
