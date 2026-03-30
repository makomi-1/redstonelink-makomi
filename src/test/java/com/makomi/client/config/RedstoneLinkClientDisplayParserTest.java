package com.makomi.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 客户端显示配置解析契约测试。
 */
@Tag("stable-core")
class RedstoneLinkClientDisplayParserTest {
	/**
	 * quick-link 序号缓存长度缺失时应回退默认值 1024。
	 */
	@Test
	void parserShouldUseDefaultQuickLinkSerialCacheMaxLengthWhenMissing() {
		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			new Properties(),
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals(1024, snapshot.quickLink().serialCacheMaxLength());
		assertEquals("key.keyboard.b", snapshot.quickLink().modeToggleKey().getName());
	}

	/**
	 * quick-link 序号缓存长度应按配置范围收敛。
	 */
	@Test
	void parserShouldClampQuickLinkSerialCacheMaxLength() {
		Properties properties = new Properties();
		properties.setProperty(RedstoneLinkClientDisplayParser.KEY_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH, "65535");

		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			properties,
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals(32768, snapshot.quickLink().serialCacheMaxLength());
	}
}
