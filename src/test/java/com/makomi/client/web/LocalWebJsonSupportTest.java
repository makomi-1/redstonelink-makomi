package com.makomi.client.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 本地网页偏好 JSON 支撑测试。
 */
@Tag("stable-core")
class LocalWebJsonSupportTest {
	/**
	 * 偏好补丁解析应基于 fallback 合并缺失字段。
	 */
	@Test
	void parsePreferencesPatchShouldMergeWithFallback() throws IOException {
		LocalWebPreferences preferences = LocalWebJsonSupport.parsePreferencesPatch(
			"""
				{
				  "themeId": "lab-minimal"
				}
				""",
			new LocalWebPreferences("en-US", "future-command")
		);

		assertEquals("en-US", preferences.language());
		assertEquals("lab-minimal", preferences.themeId());
	}

	/**
	 * 非法 JSON 请求体应被拒绝，避免把损坏内容写入本地偏好文件。
	 */
	@Test
	void parsePreferencesPatchShouldRejectInvalidJson() {
		assertThrows(
			IOException.class,
			() -> LocalWebJsonSupport.parsePreferencesPatch("[]", LocalWebPreferences.defaults())
		);
	}
}
