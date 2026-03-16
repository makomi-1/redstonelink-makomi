package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 节点类型解析的稳定契约测试。
 */
@Tag("stable-core")
class LinkNodeTypeTest {

	/**
	 * 语义词表类型应支持大小写无关解析。
	 */
	@Test
	void fromNameShouldBeCaseInsensitive() {
		assertEquals(LinkNodeType.CORE, LinkNodeType.fromName("core"));
		assertEquals(LinkNodeType.CORE, LinkNodeType.fromName("CoRe"));
		assertEquals(LinkNodeType.TRIGGER_SOURCE, LinkNodeType.fromName("triggerSource"));
		assertEquals(LinkNodeType.TRIGGER_SOURCE, LinkNodeType.fromName("TRIGGERSOURCE"));
	}

	/**
	 * 空值或未知值应回退为 CORE。
	 */
	@Test
	void fromNameShouldFallbackToCore() {
		assertEquals(LinkNodeType.CORE, LinkNodeType.fromName(null));
		assertEquals(LinkNodeType.CORE, LinkNodeType.fromName(""));
		assertEquals(LinkNodeType.CORE, LinkNodeType.fromName("unknown"));
	}
}
