package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 过滤器 far overlay 显示文本契约测试。
 */
@Tag("stable-core")
class LinkSerialOverlayRenderCommonTest {
	/**
	 * far overlay 应优先显示别名，空别名时回退过滤器标题。
	 */
	@Test
	void composeFilterDisplayTextShouldPreferAlias() {
		assertEquals("门厅A", LinkSerialOverlayRenderCommon.composeFilterDisplayText("发送过滤器", " 门厅A "));
		assertEquals("发送过滤器", LinkSerialOverlayRenderCommon.composeFilterDisplayText("发送过滤器", ""));
	}
}
