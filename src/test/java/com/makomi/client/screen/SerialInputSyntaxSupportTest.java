package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 序号输入语法 support 回归测试。
 */
@Tag("client")
class SerialInputSyntaxSupportTest {
	/**
	 * 多行输入应折叠为 `/` 分段表达式。
	 */
	@Test
	void normalizeExpressionShouldCollapseMultilineIntoSlashSegments() {
		assertEquals("1:3/5/7:8", SerialInputSyntaxSupport.normalizeExpression("1:3\r\n 5 \n7:8"));
	}

	/**
	 * 语法校验应在折叠多行后继续收集非法分段。
	 */
	@Test
	void validateShouldCollectInvalidEntriesAfterNormalize() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate("1\nx:3\n4:2");

		assertEquals("1/x:3/4:2", validation.normalizedExpression());
		assertEquals(List.of("x:3", "4:2"), validation.invalidEntries());
		assertTrue(!validation.valid());
	}
}
