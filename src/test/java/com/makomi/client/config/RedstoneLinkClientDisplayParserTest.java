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
	 * 序号外显模式缺失时应回退默认 `far`。
	 */
	@Test
	void parserShouldUseDefaultSerialOverlayModeWhenMissing() {
		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			new Properties(),
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals(RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY, snapshot.overlay().mode());
	}

	/**
	 * 序号外显模式应按当前配置键解析。
	 */
	@Test
	void parserShouldApplySerialOverlayModeFromCurrentKey() {
		Properties properties = new Properties();
		properties.setProperty(RedstoneLinkClientDisplayParser.KEY_SERIAL_OVERLAY_MODE, "off");

		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			properties,
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals(RedstoneLinkClientDisplayConfig.SerialOverlayMode.OFF, snapshot.overlay().mode());
	}

	/**
	 * 已移除的旧键不再生效，缺失当前键时保持默认 `far`。
	 */
	@Test
	void parserShouldIgnoreRemovedLegacySerialOverlayKey() {
		Properties properties = new Properties();
		properties.setProperty("client.serialOverlayEnabled", "false");

		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			properties,
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals(RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY, snapshot.overlay().mode());
	}

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
		assertEquals("key.keyboard.k", snapshot.overlay().faceVectorToggleKey().getName());
		assertEquals(false, snapshot.overlay().faceVectorEnabled());
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

	/**
	 * 智能眼镜定向方向箭头配置应按当前键和值解析。
	 */
	@Test
	void parserShouldApplySmartGlassesFaceVectorConfig() {
		Properties properties = new Properties();
		properties.setProperty(
			RedstoneLinkClientDisplayParser.KEY_SMART_GLASSES_FACE_VECTOR_TOGGLE_KEY,
			"key.keyboard.j"
		);
		properties.setProperty(
			RedstoneLinkClientDisplayParser.KEY_SMART_GLASSES_FACE_VECTOR_ENABLED,
			"true"
		);

		RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplayParser.parse(
			properties,
			LoggerFactory.getLogger("test-client-config")
		);

		assertEquals("key.keyboard.j", snapshot.overlay().faceVectorToggleKey().getName());
		assertEquals(true, snapshot.overlay().faceVectorEnabled());
	}
}
