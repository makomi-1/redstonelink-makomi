package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 网页功能权限配置解析回归测试。
 */
@Tag("stable-core")
class RedstoneLinkConfigWebPermissionParseTest {
	/**
	 * 缺省配置应让 recording / graph 两个网页功能权限都回退到默认值 2。
	 */
	@Test
	void parseShouldUseDefaultWebPermissionLevelsWhenPropertiesMissing() {
		RedstoneLinkWebConfig web = RedstoneLinkConfigTestHelper.parseServer(new Properties()).web();
		assertEquals(2, web.recordingPermissionLevel());
		assertEquals(2, web.graphPermissionLevel());
	}

	/**
	 * recording / graph 网页权限等级都应执行 0~4 的边界夹紧。
	 */
	@Test
	void parseShouldClampWebPermissionLevelsToRange() {
		Properties properties = new Properties();
		properties.setProperty("server.web.recording.permissionLevel", "-9");
		properties.setProperty("server.web.graph.permissionLevel", "99");

		RedstoneLinkWebConfig web = RedstoneLinkConfigTestHelper.parseServer(properties).web();
		assertEquals(0, web.recordingPermissionLevel());
		assertEquals(4, web.graphPermissionLevel());
	}

	/**
	 * 非法字符串输入应分别回退到各自默认值 2。
	 */
	@Test
	void parseShouldFallbackToDefaultWhenWebPermissionLevelInvalid() {
		Properties properties = new Properties();
		properties.setProperty("server.web.recording.permissionLevel", "bad");
		properties.setProperty("server.web.graph.permissionLevel", "bad");

		RedstoneLinkWebConfig web = RedstoneLinkConfigTestHelper.parseServer(properties).web();
		assertEquals(2, web.recordingPermissionLevel());
		assertEquals(2, web.graphPermissionLevel());
	}
}
