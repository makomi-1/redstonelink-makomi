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
	 * bench 命令测试模式缺省应关闭，显式配置后应生效。
	 */
	@Test
	void parseShouldApplyBenchmarkModeFlag() throws Exception {
		Object defaultsSnapshot = parseValues(new Properties());
		assertFalse(readBoolean(defaultsSnapshot, "commandBenchmarkModeEnabled"));

		Properties enabledProperties = new Properties();
		enabledProperties.setProperty("server.command.benchmarkMode.enabled", "true");
		Object enabledSnapshot = parseValues(enabledProperties);
		assertTrue(readBoolean(enabledSnapshot, "commandBenchmarkModeEnabled"));
	}

	/**
	 * 非法的 bench 命令测试模式布尔值应回退默认 false。
	 */
	@Test
	void parseShouldFallbackWhenBenchmarkModeBooleanInvalid() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("server.command.benchmarkMode.enabled", "invalid");

		Object valuesSnapshot = parseValues(properties);
		assertFalse(readBoolean(valuesSnapshot, "commandBenchmarkModeEnabled"));
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
		assertTrue(readBoolean(crossChunkValuesSnapshot, "syncTargetChunkLoadReplayEnabled"));
		assertFalse(readBoolean(crossChunkValuesSnapshot, "activationPulseRelayEnabled"));
		assertEquals(200, readInt(crossChunkValuesSnapshot, "activationPulseTtlTicks"));
		assertFalse(readBoolean(crossChunkValuesSnapshot, "activationPulsePersistentExperimental"));
		assertFalse(readBoolean(crossChunkValuesSnapshot, "activationToggleRelayEnabled"));
		assertEquals(200, readInt(crossChunkValuesSnapshot, "activationToggleTtlTicks"));
		assertFalse(readBoolean(crossChunkValuesSnapshot, "activationTogglePersistentExperimental"));
		assertFalse(readBoolean(crossChunkValuesSnapshot, "triggerSourceChunkUnloadInvalidationEnabled"));
		assertTrue(readBoolean(crossChunkValuesSnapshot, "triggerSourceInvalidationEnabled"));
		assertTrue(readBoolean(crossChunkValuesSnapshot, "queueEnabled"));
		assertEquals(200, readInt(crossChunkValuesSnapshot, "queueDefaultTtlTicks"));
		assertEquals(200, readInt(crossChunkValuesSnapshot, "retryWarnThreshold"));
		assertEquals(1000, readInt(crossChunkValuesSnapshot, "retryErrorThreshold"));
		assertEquals(2000, readInt(crossChunkValuesSnapshot, "retryDropThreshold"));
		assertEquals(99, readInt(crossChunkValuesSnapshot, "retryStage1MaxAttempts"));
		assertEquals(1, readInt(crossChunkValuesSnapshot, "retryStage1IntervalTicks"));
		assertEquals(499, readInt(crossChunkValuesSnapshot, "retryStage2MaxAttempts"));
		assertEquals(5, readInt(crossChunkValuesSnapshot, "retryStage2IntervalTicks"));
		assertEquals(999, readInt(crossChunkValuesSnapshot, "retryStage3MaxAttempts"));
		assertEquals(20, readInt(crossChunkValuesSnapshot, "retryStage3IntervalTicks"));
		assertEquals(100, readInt(crossChunkValuesSnapshot, "retryStage4IntervalTicks"));
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
		disabled.setProperty("crosschunk.syncTargetChunkLoadReplay.enabled", "false");
		Object disabledSnapshot = parseCrossChunkValues(disabled);
		assertFalse(readBoolean(disabledSnapshot, "syncSignalPersistent"));
		assertFalse(readBoolean(disabledSnapshot, "syncTargetChunkLoadReplayEnabled"));

		Properties invalid = new Properties();
		invalid.setProperty("crosschunk.syncSignalPersistent", "invalid");
		invalid.setProperty("crosschunk.syncTargetChunkLoadReplay.enabled", "invalid");
		Object invalidSnapshot = parseCrossChunkValues(invalid);
		assertTrue(readBoolean(invalidSnapshot, "syncSignalPersistent"));
		assertTrue(readBoolean(invalidSnapshot, "syncTargetChunkLoadReplayEnabled"));
	}

	/**
	 * pulse/toggle 独立 relay 与实验开关应支持显式配置，并在缺省时回退默认值。
	 */
	@Test
	void parseCrossChunkShouldApplyActivationRelayFlags() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.activation.pulse.relay.enabled", "true");
		properties.setProperty("crosschunk.activation.pulse.ttlTicks", "321");
		properties.setProperty("crosschunk.activation.pulse.persistentExperimental", "true");
		properties.setProperty("crosschunk.activation.toggle.relay.enabled", "true");
		properties.setProperty("crosschunk.activation.toggle.ttlTicks", "654");
		properties.setProperty("crosschunk.activation.toggle.persistentExperimental", "true");

		Object snapshot = parseCrossChunkValues(properties);
		assertTrue(readBoolean(snapshot, "activationPulseRelayEnabled"));
		assertEquals(321, readInt(snapshot, "activationPulseTtlTicks"));
		assertTrue(readBoolean(snapshot, "activationPulsePersistentExperimental"));
		assertTrue(readBoolean(snapshot, "activationToggleRelayEnabled"));
		assertEquals(654, readInt(snapshot, "activationToggleTtlTicks"));
		assertTrue(readBoolean(snapshot, "activationTogglePersistentExperimental"));
	}

	/**
	 * 全局跨区块持久派发队列应使用 queue 命名，并支持显式配置。
	 */
	@Test
	void parseCrossChunkShouldApplyQueueFlags() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.queue.enabled", "false");
		properties.setProperty("crosschunk.queue.defaultTtlTicks", "345");

		Object snapshot = parseCrossChunkValues(properties);
		assertFalse(readBoolean(snapshot, "queueEnabled"));
		assertEquals(345, readInt(snapshot, "queueDefaultTtlTicks"));
	}

	/**
	 * 持久 pending 的分段退避配置应支持显式配置。
	 */
	@Test
	void parseCrossChunkShouldApplyPersistentRetryStageConfig() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.retry.stage1.maxAttempts", "12");
		properties.setProperty("crosschunk.retry.stage1.intervalTicks", "3");
		properties.setProperty("crosschunk.retry.stage2.maxAttempts", "34");
		properties.setProperty("crosschunk.retry.stage2.intervalTicks", "7");
		properties.setProperty("crosschunk.retry.stage3.maxAttempts", "56");
		properties.setProperty("crosschunk.retry.stage3.intervalTicks", "11");
		properties.setProperty("crosschunk.retry.stage4.intervalTicks", "13");

		Object snapshot = parseCrossChunkValues(properties);
		assertEquals(12, readInt(snapshot, "retryStage1MaxAttempts"));
		assertEquals(3, readInt(snapshot, "retryStage1IntervalTicks"));
		assertEquals(34, readInt(snapshot, "retryStage2MaxAttempts"));
		assertEquals(7, readInt(snapshot, "retryStage2IntervalTicks"));
		assertEquals(56, readInt(snapshot, "retryStage3MaxAttempts"));
		assertEquals(11, readInt(snapshot, "retryStage3IntervalTicks"));
		assertEquals(13, readInt(snapshot, "retryStage4IntervalTicks"));
		assertEquals(2000, readInt(snapshot, "retryDropThreshold"));
	}

	/**
	 * triggerSource 两类失效事件应支持独立配置。
	 */
	@Test
	void parseCrossChunkShouldApplyTriggerSourceInvalidationFlags() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceChunkUnloadInvalidation.enabled", "true");
		properties.setProperty("crosschunk.triggerSourceInvalidation.enabled", "false");

		Object snapshot = parseCrossChunkValues(properties);
		assertTrue(readBoolean(snapshot, "triggerSourceChunkUnloadInvalidationEnabled"));
		assertFalse(readBoolean(snapshot, "triggerSourceInvalidationEnabled"));
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
