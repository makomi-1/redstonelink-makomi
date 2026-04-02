package com.makomi.config;

import com.makomi.data.LinkNodeType;
import java.util.Map;
import java.util.Set;

/**
 * 服务端配置聚合快照。
 */
record RedstoneLinkServerConfigSnapshot(
	RedstoneLinkGeneralConfig general,
	RedstoneLinkCommandConfig command,
	RedstoneLinkRateLimitConfig rateLimit,
	RedstoneLinkPrivacyConfig privacy,
	RedstoneLinkWriteControlConfig writeControl,
	RedstoneLinkInteractionConfig interaction,
	RedstoneLinkRuntimeConfig runtime,
	RedstoneLinkCrossChunkConfig crossChunk
) {
	/**
	 * @return 服务端配置默认值
	 */
	static RedstoneLinkServerConfigSnapshot defaults() {
		return new RedstoneLinkServerConfigSnapshot(
			new RedstoneLinkGeneralConfig(
				4,
				RedstoneLinkConfig.EmitterEdgeMode.RISING,
				15,
				1024,
				true,
				5,
				50
			),
			new RedstoneLinkCommandConfig(
				0,
				2,
				false,
				true,
				true,
				1024,
				1024,
				1024,
				1024,
				1024,
				1024
			),
			new RedstoneLinkRateLimitConfig(
				true,
				20,
				3072,
				600,
				400,
				24,
				16,
				12,
				8,
				4,
				3,
				6,
				4
			),
			new RedstoneLinkPrivacyConfig(
				RedstoneLinkConfig.CurrentLinksPrivacyMode.MASKED,
				0,
				2,
				2
			),
			new RedstoneLinkWriteControlConfig(
				RedstoneLinkConfig.LinkWriteControlMode.LIMITED,
				2,
				64,
				2,
				2
			),
			new RedstoneLinkInteractionConfig(
				false,
				true,
				true
			),
			new RedstoneLinkRuntimeConfig(
				true,
				true,
				40
			),
			new RedstoneLinkCrossChunkConfig(
				40,
				true,
				true,
				true,
				false,
				RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode.ALL_SYNC,
				0,
				false,
				200,
				false,
				false,
				200,
				false,
				true,
				true,
				200,
				500,
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				80,
				8,
				2,
				true,
				2,
				true,
				RedstoneLinkConfig.CrossChunkNotifyMode.SIMPLE,
				false,
				25,
				false,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of(),
				Map.of(),
				new RedstoneLinkCrossChunkRetryConfig(
					200,
					1000,
					2000,
					99,
					1,
					499,
					5,
					999,
					20,
					100
				)
			)
		);
	}
}
