package com.makomi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 序号解析工具的稳定核心测试。
 */
@Tag("stable-core")
class SerialParseUtilTest {
	/**
	 * 空输入应返回空结果且不超限。
	 */
	@Test
	void parseTargetsShouldReturnEmptyForBlank() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("  ", 10);
		assertTrue(result.targets().isEmpty());
		assertTrue(result.invalidEntries().isEmpty());
		assertFalse(result.exceedLimit());
	}

	/**
	 * 应忽略无效条目并仅保留正整数序号。
	 */
	@Test
	void parseTargetsShouldFilterInvalidEntries() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("1, -2, abc, 3", 10);
		assertEquals(Set.of(1L, 3L), result.targets());
		assertEquals(List.of("-2", "abc"), result.invalidEntries());
		assertFalse(result.exceedLimit());
	}

	/**
	 * 超出数量上限时应标记超限并返回空目标集。
	 */
	@Test
	void parseTargetsShouldStopWhenExceedLimit() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("1 2 3", 2);
		assertTrue(result.targets().isEmpty());
		assertTrue(result.exceedLimit());
	}
}
