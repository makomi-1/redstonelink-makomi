package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 交互门禁与跨区块命令配置解析的组合契约测试。
 */
@Tag("stable-core")
class RedstoneLinkConfigInteractionAndCrossChunkParseTest {
	/**
	 * 交互门禁配置缺省时，应回退到默认策略。
	 */
	@Test
	void parseShouldUseInteractionDefaultsWhenPropertiesMissing() throws Exception {
		Object valuesSnapshot = parseValues(new Properties());
		assertTrue(readBoolean(valuesSnapshot, "requireSneakToOpenPairing"));
		assertTrue(readBoolean(valuesSnapshot, "requireSneakToOpenLinkerPairing"));
		assertTrue(readBoolean(valuesSnapshot, "requireEmptyOffhandToOpenPairing"));
	}

	/**
	 * 交互门禁配置给定明确值时，应按配置生效。
	 */
	@Test
	void parseShouldApplyConfiguredInteractionFlags() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("interaction.requireSneakToOpenPairing", "false");
		properties.setProperty("interaction.requireSneakToOpenLinkerPairing", "true");
		properties.setProperty("interaction.requireEmptyOffhandToOpenPairing", "false");

		Object valuesSnapshot = parseValues(properties);
		assertFalse(readBoolean(valuesSnapshot, "requireSneakToOpenPairing"));
		assertTrue(readBoolean(valuesSnapshot, "requireSneakToOpenLinkerPairing"));
		assertFalse(readBoolean(valuesSnapshot, "requireEmptyOffhandToOpenPairing"));
	}

	/**
	 * 非法布尔配置应回退默认值，避免异常配置破坏门禁语义。
	 */
	@Test
	void parseShouldFallbackToDefaultWhenInteractionBooleanInvalid() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("interaction.requireSneakToOpenPairing", "invalid");
		properties.setProperty("interaction.requireSneakToOpenLinkerPairing", "invalid");
		properties.setProperty("interaction.requireEmptyOffhandToOpenPairing", "invalid");

		Object valuesSnapshot = parseValues(properties);
		assertTrue(readBoolean(valuesSnapshot, "requireSneakToOpenPairing"));
		assertTrue(readBoolean(valuesSnapshot, "requireSneakToOpenLinkerPairing"));
		assertTrue(readBoolean(valuesSnapshot, "requireEmptyOffhandToOpenPairing"));
	}

	/**
	 * 跨区块命令缺省配置应回退 enabled=true、permissionLevel=2。
	 */
	@Test
	void parseCrossChunkShouldUseDefaultCommandGateWhenPropertyMissing() throws Exception {
		Object crossChunkValuesSnapshot = parseCrossChunkValues(new Properties());
		assertTrue(readBoolean(crossChunkValuesSnapshot, "commandEnabled"));
		assertEquals(2, readInt(crossChunkValuesSnapshot, "commandPermissionLevel"));
		assertEquals(500, readInt(crossChunkValuesSnapshot, "dispatchMaxPerTick"));
		assertTrue(readBoolean(crossChunkValuesSnapshot, "syncSignalPersistent"));
	}

	/**
	 * 跨区块命令权限等级应执行 0~4 的边界夹紧。
	 */
	@Test
	void parseCrossChunkShouldClampCommandPermissionLevelToRange() throws Exception {
		Properties lowProperties = new Properties();
		lowProperties.setProperty("crosschunk.command.permissionLevel", "-5");
		Object lowSnapshot = parseCrossChunkValues(lowProperties);
		assertEquals(0, readInt(lowSnapshot, "commandPermissionLevel"));

		Properties highProperties = new Properties();
		highProperties.setProperty("crosschunk.command.permissionLevel", "99");
		Object highSnapshot = parseCrossChunkValues(highProperties);
		assertEquals(4, readInt(highSnapshot, "commandPermissionLevel"));
	}

	/**
	 * 跨区块命令开关非法配置应回退默认启用，保持兼容行为。
	 */
	@Test
	void parseCrossChunkShouldFallbackWhenCommandEnabledInvalid() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.command.enabled", "not-a-boolean");
		Object crossChunkValuesSnapshot = parseCrossChunkValues(properties);
		assertTrue(readBoolean(crossChunkValuesSnapshot, "commandEnabled"));
	}

	/**
	 * 跨区块每 tick 派发预算应执行 1~20000 的边界夹紧。
	 */
	@Test
	void parseCrossChunkShouldClampDispatchMaxPerTickToRange() throws Exception {
		Properties lowProperties = new Properties();
		lowProperties.setProperty("crosschunk.dispatch.maxPerTick", "-9");
		Object lowSnapshot = parseCrossChunkValues(lowProperties);
		assertEquals(1, readInt(lowSnapshot, "dispatchMaxPerTick"));

		Properties highProperties = new Properties();
		highProperties.setProperty("crosschunk.dispatch.maxPerTick", "900000");
		Object highSnapshot = parseCrossChunkValues(highProperties);
		assertEquals(20_000, readInt(highSnapshot, "dispatchMaxPerTick"));
	}

	/**
	 * SYNC 持久化开关应支持显式配置，并在非法值时回退默认 true。
	 */
	@Test
	void parseCrossChunkShouldApplySyncSignalPersistentFlag() throws Exception {
		Properties disabled = new Properties();
		disabled.setProperty("crosschunk.syncSignalPersistent", "false");
		Object disabledSnapshot = parseCrossChunkValues(disabled);
		assertFalse(readBoolean(disabledSnapshot, "syncSignalPersistent"));

		Properties invalid = new Properties();
		invalid.setProperty("crosschunk.syncSignalPersistent", "invalid");
		Object invalidSnapshot = parseCrossChunkValues(invalid);
		assertTrue(readBoolean(invalidSnapshot, "syncSignalPersistent"));
	}

	/**
	 * 反射调用基础配置解析入口。
	 */
	private static Object parseValues(Properties properties) throws Exception {
		Method parseMethod = RedstoneLinkConfig.class.getDeclaredMethod("parse", Properties.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, properties);
	}

	/**
	 * 反射调用跨区块配置解析入口。
	 */
	private static Object parseCrossChunkValues(Properties properties) throws Exception {
		Method parseMethod = RedstoneLinkConfig.class.getDeclaredMethod("parseCrossChunk", Properties.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, properties);
	}

	/**
	 * 反射读取 record 布尔访问器。
	 */
	private static boolean readBoolean(Object recordSnapshot, String accessorName) throws Exception {
		Method accessor = recordSnapshot.getClass().getDeclaredMethod(accessorName);
		accessor.setAccessible(true);
		return (boolean) accessor.invoke(recordSnapshot);
	}

	/**
	 * 反射读取 record 整型访问器。
	 */
	private static int readInt(Object recordSnapshot, String accessorName) throws Exception {
		Method accessor = recordSnapshot.getClass().getDeclaredMethod(accessorName);
		accessor.setAccessible(true);
		return (int) accessor.invoke(recordSnapshot);
	}
}
