package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 节点别名展示格式回归测试。
 */
@Tag("stable-core")
class NodeAliasDisplayUtilTest {
	@Test
	void formatDisplayTextShouldCombineAliasAndSerial() {
		assertEquals("大门1(#12)", NodeAliasDisplayUtil.formatDisplayText("大门1", 12L));
		assertEquals("#12", NodeAliasDisplayUtil.formatDisplayText("", 12L));
		assertEquals("-", NodeAliasDisplayUtil.formatDisplayText("大门1", 0L));
	}
}
