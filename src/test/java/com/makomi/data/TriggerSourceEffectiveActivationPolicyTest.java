package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.makomi.config.RedstoneLinkConfigTestHelper;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `triggerSource` 有效激活语义测试。
 */
class TriggerSourceEffectiveActivationPolicyTest {
	/**
	 * 默认配置下，`sync` 来源 replay 应按“非硬下线”筛选。
	 */
	@Test
	void resolveSyncReplayRequirementShouldUseNonHardDownByDefault() throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(new Properties(), () ->
			assertEquals(
				TriggerSourceEffectiveActivationPolicy.EffectiveActivationRequirement.NON_HARD_DOWN,
				TriggerSourceEffectiveActivationPolicy.resolveSyncReplayRequirement()
			)
		);
	}

	/**
	 * 开启 context-detach invalidation 后，`sync` 来源 replay 应提升到“非下线”筛选。
	 */
	@Test
	void resolveSyncReplayRequirementShouldUseNonOfflineWhenContextDetachInvalidationEnabled() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceContextDetachInvalidation.enabled", "true");
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, () ->
			assertEquals(
				TriggerSourceEffectiveActivationPolicy.EffectiveActivationRequirement.NON_OFFLINE,
				TriggerSourceEffectiveActivationPolicy.resolveSyncReplayRequirement()
			)
		);
	}

	/**
	 * 旧的 hard invalidation 配置键不应改变当前固定开启的硬下线过滤。
	 */
	@Test
	void hardInvalidationShouldStayEnabledWhenLegacyPropertyIsFalse() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.triggerSourceHardInvalidation.enabled", "false");
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, () -> {
			assertTrue(TriggerSourceEffectiveActivationPolicy.hardDownFilteringEnabled());
			assertEquals(
				TriggerSourceEffectiveActivationPolicy.EffectiveActivationRequirement.NON_HARD_DOWN,
				TriggerSourceEffectiveActivationPolicy.resolveSyncReplayRequirement()
			);
		});
	}

	/**
	 * “非硬下线”口径下，只要来源仍保留节点登记，就允许参与 replay。
	 */
	@Test
	void filterReplayEligibleSyncSourcesShouldAllowRegisteredSourcesForNonHardDown() {
		Set<Long> filtered = TriggerSourceEffectiveActivationPolicy.filterReplayEligibleSyncSources(
			TriggerSourceEffectiveActivationPolicy.EffectiveActivationRequirement.NON_HARD_DOWN,
			Set.of(11L, 12L, 13L),
			sourceSerial -> sourceSerial == 11L || sourceSerial == 13L,
			sourceSerial -> sourceSerial == 11L
		);
		assertEquals(Set.of(11L, 13L), filtered);
	}

	/**
	 * “非下线”口径下，只有真正在线就绪的来源才允许参与 replay。
	 */
	@Test
	void filterReplayEligibleSyncSourcesShouldAllowOnlyOnlineReadySourcesForNonOffline() {
		Set<Long> filtered = TriggerSourceEffectiveActivationPolicy.filterReplayEligibleSyncSources(
			TriggerSourceEffectiveActivationPolicy.EffectiveActivationRequirement.NON_OFFLINE,
			Set.of(21L, 22L, 23L),
			sourceSerial -> true,
			sourceSerial -> sourceSerial == 22L
		);
		assertEquals(Set.of(22L), filtered);
	}
}
