package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
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
	void parseShouldUseDefaultPermissionLevelWhenPropertyMissing() throws Exception {
		assertEquals(0, parsePermissionLevel(null));
	}

	/**
	 * 小于下限时应被夹紧到 0。
	 */
	@Test
	void parseShouldClampPermissionLevelToMinimum() throws Exception {
		assertEquals(0, parsePermissionLevel("-1"));
	}

	/**
	 * 大于上限时应被夹紧到 4。
	 */
	@Test
	void parseShouldClampPermissionLevelToMaximum() throws Exception {
		assertEquals(4, parsePermissionLevel("9"));
	}

	/**
	 * 非法字符串应回退到默认权限等级 0。
	 */
	@Test
	void parseShouldFallbackToDefaultWhenPermissionLevelIsInvalid() throws Exception {
		assertEquals(0, parsePermissionLevel("not-a-number"));
	}

	/**
	 * 通过反射调用私有 parse(Properties) 并读取 Values 快照中的权限等级字段。
	 */
	private static int parsePermissionLevel(String rawPermissionLevel) throws Exception {
		Properties properties = new Properties();
		if (rawPermissionLevel != null) {
			properties.setProperty("server.command.permissionLevel", rawPermissionLevel);
		}
		Object valuesSnapshot = parseValues(properties);
		Method accessor = valuesSnapshot.getClass().getDeclaredMethod("commandPermissionLevel");
		accessor.setAccessible(true);
		return (int) accessor.invoke(valuesSnapshot);
	}

	/**
	 * 反射执行配置解析入口，保持测试覆盖真实解析路径。
	 */
	private static Object parseValues(Properties properties) throws Exception {
		Method parseMethod = RedstoneLinkConfig.class.getDeclaredMethod("parse", Properties.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, properties);
	}
}
