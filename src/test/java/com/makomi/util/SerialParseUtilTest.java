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
		assertTrue(result.duplicateEntries().isEmpty());
		assertFalse(result.exceedLimit());
	}

	/**
	 * 应支持单值和区间混合输入，并记录重复条目。
	 */
	@Test
	void parseTargetsShouldParseMixedSegmentsAndCollectDuplicates() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("1:3/2/10/12:13/", 20);
		assertEquals(Set.of(1L, 2L, 3L, 10L, 12L, 13L), result.targets());
		assertTrue(result.invalidEntries().isEmpty());
		assertEquals(List.of(2L), result.duplicateEntries());
		assertFalse(result.exceedLimit());
	}

	/**
	 * 非法段应被收集并反馈，不影响合法段解析。
	 */
	@Test
	void parseTargetsShouldCollectInvalidSegments() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("1/a:3/4:2//3:b/7", 20);
		assertEquals(Set.of(1L, 7L), result.targets());
		assertEquals(List.of("a:3", "4:2", "3:b"), result.invalidEntries());
		assertTrue(result.duplicateEntries().isEmpty());
		assertFalse(result.exceedLimit());
	}

	/**
	 * 超出数量上限时应标记超限并返回空目标集合。
	 */
	@Test
	void parseTargetsShouldStopWhenExceedLimit() {
		SerialParseUtil.TargetParseResult result = SerialParseUtil.parseTargets("1:3/5", 3);
		assertTrue(result.targets().isEmpty());
		assertTrue(result.exceedLimit());
	}
}
