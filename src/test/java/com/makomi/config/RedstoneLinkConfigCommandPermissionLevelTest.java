package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * server.command.permissionLevel 解析与边界夹紧回归测试。
 */
@Tag("stable-core")
class RedstoneLinkConfigCommandPermissionLevelTest {

	/**
	 * 缺省配置应回退到默认权限等级 0。
	 */
	@Test
	void parseShouldUseDefaultPermissionLevelWhenPropertyMissing() {
		assertEquals(0, parsePermissionLevel(null));
	}

	/**
	 * 小于下限时应被夹紧到 0。
	 */
	@Test
	void parseShouldClampPermissionLevelToMinimum() {
		assertEquals(0, parsePermissionLevel("-1"));
	}

	/**
	 * 大于上限时应被夹紧到 4。
	 */
	@Test
	void parseShouldClampPermissionLevelToMaximum() {
		assertEquals(4, parsePermissionLevel("9"));
	}

	/**
	 * 非法字符串应回退到默认权限等级 0。
	 */
	@Test
	void parseShouldFallbackToDefaultWhenPermissionLevelIsInvalid() {
		assertEquals(0, parsePermissionLevel("not-a-number"));
	}

	/**
	 * 调用 parser 并读取命令配置中的权限等级。
	 */
	private static int parsePermissionLevel(String rawPermissionLevel) {
		Properties properties = new Properties();
		if (rawPermissionLevel != null) {
			properties.setProperty("server.command.permissionLevel", rawPermissionLevel);
		}
		return RedstoneLinkConfigTestHelper.parseServer(properties).command().permissionLevel();
	}
}
