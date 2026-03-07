package com.makomi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 构建测试基座的最小冒烟用例。
 * <p>
 * 该用例用于验证 JUnit 5 测试任务链已正确接入。
 * </p>
 */
@Tag("stable-core")
class BuildSanityTest {

	/**
	 * 验证测试任务可被发现并执行。
	 */
	@Test
	void testRunnerIsAvailable() {
		assertTrue(true, "测试运行器应可正常执行");
	}
}
