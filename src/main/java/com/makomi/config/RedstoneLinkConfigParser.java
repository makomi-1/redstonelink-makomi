package com.makomi.config;

import java.util.Properties;

/**
 * 服务端主配置解析器。
 * <p>
 * 负责将原始 properties 解析为最终按域配置快照。
 * </p>
 */
final class RedstoneLinkConfigParser {
	private RedstoneLinkConfigParser() {
	}

	/**
	 * 解析服务端配置聚合快照。
	 */
	static RedstoneLinkServerConfigSnapshot parse(Properties props) {
		return new RedstoneLinkServerConfigSnapshot(
			parseGeneral(props),
			parseCommand(props),
			parseRateLimit(props),
			parsePrivacy(props),
			parseWriteControl(props),
			parseInteraction(props),
			parseRuntime(props),
			RedstoneLinkCrossChunkConfigParser.parse(props)
		);
	}

	/**
	 * 解析基础通用配置。
	 */
	private static RedstoneLinkGeneralConfig parseGeneral(Properties props) {
		return new RedstoneLinkGeneralConfig(
			RedstoneLinkConfigParseSupport.parseInt(props, "server.pulseDurationTicks", 4, 1, 240),
			RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue(props.getProperty("server.emitterEdgeMode", "rising")),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.coreOutputPower", 15, 0, 15),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.maxTargetsPerSetLinks", 1024, 1, 4096),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.allowOfflineTargetBinding", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.statePanel.refreshHz", 5, 1, 20),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.statePanel.maxSubscriptions", 50, 1, 512)
		);
	}

	/**
	 * 解析命令配置。
	 */
	private static RedstoneLinkCommandConfig parseCommand(Properties props) {
		return new RedstoneLinkCommandConfig(
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.permissionLevel", 0, 0, 4),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.otherPermissionLevel", 2, 0, 4),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.command.benchmarkMode.enabled", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.command.input.enabled", true),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.command.nodeTrace.enabled", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.linkSet.maxInputLength", 1024, 64, 32768),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.activate.batchMaxSerials", 1024, 1, 65536),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.retire.batchMaxSerials", 1024, 1, 65536),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.privacy.currentLinksMask.maxSetSerials", 1024, 1, 65536),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.writeControl.protected.maxSetSerials", 1024, 1, 65536),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.crosschunk.whitelist.maxSetSerials", 1024, 1, 65536)
		);
	}

	/**
	 * 解析命令限流配置。
	 */
	private static RedstoneLinkRateLimitConfig parseRateLimit(Properties props) {
		return new RedstoneLinkRateLimitConfig(
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.command.rateLimit.enabled", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.windowTicks", 20, 1, 2000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.global.capacity", 3072, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.tier.baseCapacity", 600, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.tier.stepPerLevel", 400, 0, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actor.baseCapacity", 24, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actor.stepPerLevel", 16, 0, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.linkRw.baseCapacity", 12, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.linkRw.stepPerLevel", 8, 0, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.graphWrite.baseCapacity", 24, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.graphWrite.stepPerLevel", 16, 0, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.crosschunk.baseCapacity", 4, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.crosschunk.stepPerLevel", 3, 0, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.other.baseCapacity", 6, 1, 200_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.command.rateLimit.actorGroup.other.stepPerLevel", 4, 0, 200_000)
		);
	}

	/**
	 * 解析当前连接隐私配置。
	 */
	private static RedstoneLinkPrivacyConfig parsePrivacy(Properties props) {
		return new RedstoneLinkPrivacyConfig(
			RedstoneLinkConfig.CurrentLinksPrivacyMode.fromConfigValue(
				props.getProperty("server.currentLinksPrivacy.mode", "masked")
			),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.currentLinksPrivacy.overlayResponsePermissionLevel", 0, 0, 4),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.currentLinksPrivacy.viewPermissionLevel", 2, 0, 4),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.currentLinksPrivacy.managePermissionLevel", 2, 0, 4)
		);
	}

	/**
	 * 解析链接写控配置。
	 */
	private static RedstoneLinkWriteControlConfig parseWriteControl(Properties props) {
		return new RedstoneLinkWriteControlConfig(
			RedstoneLinkConfig.LinkWriteControlMode.fromConfigValue(props.getProperty("server.linkWriteControl.mode", "limited")),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.linkWriteControl.limited.permissionLevel", 2, 0, 4),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.linkWriteControl.limited.maxSetSize", 64, 1, 4096),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.linkWriteControl.protected.permissionLevel", 2, 0, 4),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.linkWriteControl.protected.managePermissionLevel", 2, 0, 4)
		);
	}

	/**
	 * 解析交互门禁配置。
	 */
	private static RedstoneLinkInteractionConfig parseInteraction(Properties props) {
		return new RedstoneLinkInteractionConfig(
			RedstoneLinkConfigParseSupport.parseBoolean(props, "interaction.requireSneakToOpenPairing", true),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "interaction.requireSneakToOpenLinkerPairing", true),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "interaction.requireEmptyOffhandToOpenPairing", true)
		);
	}

	/**
	 * 解析运行期配置。
	 */
	private static RedstoneLinkRuntimeConfig parseRuntime(Properties props) {
		return new RedstoneLinkRuntimeConfig(
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.runtime.loadResync.core.enabled", true),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "server.runtime.loadResync.triggerSource.enabled", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "server.runtime.loadResync.maxRetry", 40, 0, 10_000)
		);
	}
}
