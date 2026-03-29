package com.makomi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	void parseShouldUseInteractionDefaultsWhenPropertiesMissing() {
		RedstoneLinkServerConfigSnapshot snapshot = RedstoneLinkConfigTestHelper.parseServer(new Properties());
		assertTrue(snapshot.interaction().requireSneakToOpenPairing());
		assertTrue(snapshot.interaction().requireSneakToOpenLinkerPairing());
		assertTrue(snapshot.interaction().requireEmptyOffhandToOpenPairing());
	}

	/**
	 * 交互门禁配置给定明确值时，应按配置生效。
	 */
	@Test
	void parseShouldApplyConfiguredInteractionFlags() {
		Properties properties = new Properties();
		properties.setProperty("interaction.requireSneakToOpenPairing", "false");
		properties.setProperty("interaction.requireSneakToOpenLinkerPairing", "true");
		properties.setProperty("interaction.requireEmptyOffhandToOpenPairing", "false");

		RedstoneLinkServerConfigSnapshot snapshot = RedstoneLinkConfigTestHelper.parseServer(properties);
		assertFalse(snapshot.interaction().requireSneakToOpenPairing());
		assertTrue(snapshot.interaction().requireSneakToOpenLinkerPairing());
		assertFalse(snapshot.interaction().requireEmptyOffhandToOpenPairing());
	}

	/**
	 * bench 命令测试模式缺省应关闭，显式配置后应生效。
	 */
	@Test
	void parseShouldApplyBenchmarkModeFlag() {
		RedstoneLinkServerConfigSnapshot defaultsSnapshot = RedstoneLinkConfigTestHelper.parseServer(new Properties());
		assertFalse(defaultsSnapshot.command().benchmarkModeEnabled());

		Properties enabledProperties = new Properties();
		enabledProperties.setProperty("server.command.benchmarkMode.enabled", "true");
		RedstoneLinkServerConfigSnapshot enabledSnapshot = RedstoneLinkConfigTestHelper.parseServer(enabledProperties);
		assertTrue(enabledSnapshot.command().benchmarkModeEnabled());
	}

	/**
	 * 非法的 bench 命令测试模式布尔值应回退默认 false。
	 */
	@Test
	void parseShouldFallbackWhenBenchmarkModeBooleanInvalid() {
		Properties properties = new Properties();
		properties.setProperty("server.command.benchmarkMode.enabled", "invalid");

		RedstoneLinkServerConfigSnapshot snapshot = RedstoneLinkConfigTestHelper.parseServer(properties);
		assertFalse(snapshot.command().benchmarkModeEnabled());
	}

	/**
	 * 非法布尔配置应回退默认值，避免异常配置破坏门禁语义。
	 */
	@Test
	void parseShouldFallbackToDefaultWhenInteractionBooleanInvalid() {
		Properties properties = new Properties();
		properties.setProperty("interaction.requireSneakToOpenPairing", "invalid");
		properties.setProperty("interaction.requireSneakToOpenLinkerPairing", "invalid");
		properties.setProperty("interaction.requireEmptyOffhandToOpenPairing", "invalid");

		RedstoneLinkServerConfigSnapshot snapshot = RedstoneLinkConfigTestHelper.parseServer(properties);
		assertTrue(snapshot.interaction().requireSneakToOpenPairing());
		assertTrue(snapshot.interaction().requireSneakToOpenLinkerPairing());
		assertTrue(snapshot.interaction().requireEmptyOffhandToOpenPairing());
	}

	/**
	 * 近外显回包权限缺省应回退到 0，并执行 0~4 的边界夹紧。
	 */
	@Test
	void parseShouldApplyNearOverlayResponsePermissionLevel() {
		RedstoneLinkServerConfigSnapshot defaultsSnapshot = RedstoneLinkConfigTestHelper.parseServer(new Properties());
		assertEquals(0, defaultsSnapshot.privacy().overlayResponsePermissionLevel());

		Properties highProperties = new Properties();
		highProperties.setProperty("server.currentLinksPrivacy.overlayResponsePermissionLevel", "99");
		RedstoneLinkServerConfigSnapshot highSnapshot = RedstoneLinkConfigTestHelper.parseServer(highProperties);
		assertEquals(4, highSnapshot.privacy().overlayResponsePermissionLevel());
	}

	/**
	 * 跨区块命令缺省配置应回退 enabled=true、permissionLevel=2。
	 */
	@Test
	void parseCrossChunkShouldUseDefaultCommandGateWhenPropertyMissing() {
		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(new Properties());
		assertTrue(snapshot.commandEnabled());
		assertEquals(2, snapshot.commandPermissionLevel());
		assertEquals(500, snapshot.dispatchMaxPerTick());
		assertTrue(snapshot.syncSignalPersistent());
		assertTrue(snapshot.syncTargetChunkLoadReplayEnabled());
		assertTrue(snapshot.syncTargetChunkLoadReplayImmediateAttemptFirst());
		assertFalse(snapshot.activationPulseRelayEnabled());
		assertEquals(200, snapshot.activationPulseTtlTicks());
		assertFalse(snapshot.activationPulsePersistentExperimental());
		assertFalse(snapshot.activationToggleRelayEnabled());
		assertEquals(200, snapshot.activationToggleTtlTicks());
		assertFalse(snapshot.activationTogglePersistentExperimental());
		assertFalse(snapshot.triggerSourceChunkUnloadInvalidationEnabled());
		assertTrue(snapshot.triggerSourceInvalidationEnabled());
		assertTrue(snapshot.queueEnabled());
		assertEquals(200, snapshot.queueDefaultTtlTicks());
		assertEquals(200, snapshot.retry().warnThreshold());
		assertEquals(1000, snapshot.retry().errorThreshold());
		assertEquals(2000, snapshot.retry().dropThreshold());
		assertEquals(99, snapshot.retry().stage1MaxAttempts());
		assertEquals(1, snapshot.retry().stage1IntervalTicks());
		assertEquals(499, snapshot.retry().stage2MaxAttempts());
		assertEquals(5, snapshot.retry().stage2IntervalTicks());
		assertEquals(999, snapshot.retry().stage3MaxAttempts());
		assertEquals(20, snapshot.retry().stage3IntervalTicks());
		assertEquals(100, snapshot.retry().stage4IntervalTicks());
	}

	/**
	 * 跨区块命令权限等级应执行 0~4 的边界夹紧。
	 */
	@Test
	void parseCrossChunkShouldClampCommandPermissionLevelToRange() {
		Properties lowProperties = new Properties();
		lowProperties.setProperty("crosschunk.command.permissionLevel", "-5");
		RedstoneLinkCrossChunkConfig lowSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(lowProperties);
		assertEquals(0, lowSnapshot.commandPermissionLevel());

		Properties highProperties = new Properties();
		highProperties.setProperty("crosschunk.command.permissionLevel", "99");
		RedstoneLinkCrossChunkConfig highSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(highProperties);
		assertEquals(4, highSnapshot.commandPermissionLevel());
	}

	/**
	 * 跨区块命令开关非法配置应回退默认启用，保持兼容行为。
	 */
	@Test
	void parseCrossChunkShouldFallbackWhenCommandEnabledInvalid() {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.command.enabled", "not-a-boolean");
		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(properties);
		assertTrue(snapshot.commandEnabled());
	}

	/**
	 * 跨区块每 tick 派发预算应执行 1~20000 的边界夹紧。
	 */
	@Test
	void parseCrossChunkShouldClampDispatchMaxPerTickToRange() {
		Properties lowProperties = new Properties();
		lowProperties.setProperty("crosschunk.dispatch.maxPerTick", "-9");
		RedstoneLinkCrossChunkConfig lowSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(lowProperties);
		assertEquals(1, lowSnapshot.dispatchMaxPerTick());

		Properties highProperties = new Properties();
		highProperties.setProperty("crosschunk.dispatch.maxPerTick", "900000");
		RedstoneLinkCrossChunkConfig highSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(highProperties);
		assertEquals(20_000, highSnapshot.dispatchMaxPerTick());
	}

	/**
	 * SYNC 持久化开关应支持显式配置，并在非法值时回退默认 true。
	 */
	@Test
	void parseCrossChunkShouldApplySyncSignalPersistentFlag() {
		Properties disabled = new Properties();
		disabled.setProperty("crosschunk.syncSignalPersistent", "false");
		disabled.setProperty("crosschunk.syncTargetChunkLoadReplay.enabled", "false");
		disabled.setProperty("crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst", "false");
		RedstoneLinkCrossChunkConfig disabledSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(disabled);
		assertFalse(disabledSnapshot.syncSignalPersistent());
		assertFalse(disabledSnapshot.syncTargetChunkLoadReplayEnabled());
		assertFalse(disabledSnapshot.syncTargetChunkLoadReplayImmediateAttemptFirst());

		Properties invalid = new Properties();
		invalid.setProperty("crosschunk.syncSignalPersistent", "invalid");
		invalid.setProperty("crosschunk.syncTargetChunkLoadReplay.enabled", "invalid");
		invalid.setProperty("crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst", "invalid");
		RedstoneLinkCrossChunkConfig invalidSnapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(invalid);
		assertTrue(invalidSnapshot.syncSignalPersistent());
		assertTrue(invalidSnapshot.syncTargetChunkLoadReplayEnabled());
		assertTrue(invalidSnapshot.syncTargetChunkLoadReplayImmediateAttemptFirst());
	}

	/**
	 * pulse/toggle 独立 relay 与实验开关应支持显式配置，并在缺省时回退默认值。
	 */
	@Test
	void parseCrossChunkShouldApplyActivationRelayFlags() {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.activation.pulse.relay.enabled", "true");
		properties.setProperty("crosschunk.activation.pulse.ttlTicks", "321");
		properties.setProperty("crosschunk.activation.pulse.persistentExperimental", "true");
		properties.setProperty("crosschunk.activation.toggle.relay.enabled", "true");
		properties.setProperty("crosschunk.activation.toggle.ttlTicks", "654");
		properties.setProperty("crosschunk.activation.toggle.persistentExperimental", "true");

		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(properties);
		assertTrue(snapshot.activationPulseRelayEnabled());
		assertEquals(321, snapshot.activationPulseTtlTicks());
		assertTrue(snapshot.activationPulsePersistentExperimental());
		assertTrue(snapshot.activationToggleRelayEnabled());
		assertEquals(654, snapshot.activationToggleTtlTicks());
		assertTrue(snapshot.activationTogglePersistentExperimental());
	}

	/**
	 * 全局跨区块持久派发队列应使用 queue 命名，并支持显式配置。
	 */
	@Test
	void parseCrossChunkShouldApplyQueueFlags() {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.queue.enabled", "false");
		properties.setProperty("crosschunk.queue.defaultTtlTicks", "345");

		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(properties);
		assertFalse(snapshot.queueEnabled());
		assertEquals(345, snapshot.queueDefaultTtlTicks());
	}

	/**
	 * 持久 pending 的分段退避配置应支持显式配置。
	 */
	@Test
	void parseCrossChunkShouldApplyPersistentRetryStageConfig() {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.retry.stage1.maxAttempts", "12");
		properties.setProperty("crosschunk.retry.stage1.intervalTicks", "3");
		properties.setProperty("crosschunk.retry.stage2.maxAttempts", "34");
		properties.setProperty("crosschunk.retry.stage2.intervalTicks", "7");
		properties.setProperty("crosschunk.retry.stage3.maxAttempts", "56");
		properties.setProperty("crosschunk.retry.stage3.intervalTicks", "11");
		properties.setProperty("crosschunk.retry.stage4.intervalTicks", "13");

		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(properties);
		assertEquals(12, snapshot.retry().stage1MaxAttempts());
		assertEquals(3, snapshot.retry().stage1IntervalTicks());
		assertEquals(34, snapshot.retry().stage2MaxAttempts());
		assertEquals(7, snapshot.retry().stage2IntervalTicks());
		assertEquals(56, snapshot.retry().stage3MaxAttempts());
		assertEquals(11, snapshot.retry().stage3IntervalTicks());
		assertEquals(13, snapshot.retry().stage4IntervalTicks());
		assertEquals(2000, snapshot.retry().dropThreshold());
	}

	/**
	 * triggerSource 两类失效事件应支持独立配置。
	 */
	@Test
	void parseCrossChunkShouldApplyTriggerSourceInvalidationFlags() {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceChunkUnloadInvalidation.enabled", "true");
		properties.setProperty("crosschunk.triggerSourceInvalidation.enabled", "false");

		RedstoneLinkCrossChunkConfig snapshot = RedstoneLinkConfigTestHelper.parseCrossChunk(properties);
		assertTrue(snapshot.triggerSourceChunkUnloadInvalidationEnabled());
		assertFalse(snapshot.triggerSourceInvalidationEnabled());
	}
}