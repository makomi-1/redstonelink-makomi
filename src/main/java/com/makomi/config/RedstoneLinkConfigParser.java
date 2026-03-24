package com.makomi.config;

import java.util.Locale;
import java.util.Properties;

/**
 * 基础配置解析器。
 * <p>
 * 负责主配置快照解析与通用基础类型收敛，不承载跨区块专属配置。
 * </p>
 */
final class RedstoneLinkConfigParser {
	private RedstoneLinkConfigParser() {
	}

	/**
	 * 解析基础配置快照。
	 */
	static RedstoneLinkConfigValues parse(Properties props) {
		return new RedstoneLinkConfigValues(
			parseInt(props, "server.pulseDurationTicks", 4, 1, 40),
			RedstoneLinkConfig.EmitterEdgeMode.fromConfigValue(props.getProperty("server.emitterEdgeMode", "rising")),
			parseInt(props, "server.coreOutputPower", 15, 0, 15),
			parseInt(props, "server.maxTargetsPerSetLinks", 1024, 1, 4096),
			parseBoolean(props, "server.allowOfflineTargetBinding", true),
			parseInt(props, "server.command.permissionLevel", 0, 0, 4),
			parseInt(props, "server.command.otherPermissionLevel", 2, 0, 4),
			parseBoolean(props, "server.command.benchmarkMode.enabled", false),
			parseBoolean(props, "server.command.input.enabled", true),
			parseBoolean(props, "server.command.nodeTrace.enabled", true),
			parseBoolean(props, "server.runtime.loadResync.core.enabled", true),
			parseBoolean(props, "server.runtime.loadResync.triggerSource.enabled", true),
			parseInt(props, "server.runtime.loadResync.maxRetry", 40, 0, 10_000),
			parseBoolean(props, "server.command.rateLimit.enabled", true),
			parseInt(props, "server.command.rateLimit.windowTicks", 20, 1, 2000),
			parseInt(props, "server.command.rateLimit.global.capacity", 3072, 1, 200_000),
			parseInt(props, "server.command.rateLimit.tier.baseCapacity", 600, 1, 200_000),
			parseInt(props, "server.command.rateLimit.tier.stepPerLevel", 400, 0, 200_000),
			parseInt(props, "server.command.rateLimit.actor.baseCapacity", 24, 1, 200_000),
			parseInt(props, "server.command.rateLimit.actor.stepPerLevel", 16, 0, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.linkRw.baseCapacity", 12, 1, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.linkRw.stepPerLevel", 8, 0, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.crosschunk.baseCapacity", 4, 1, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.crosschunk.stepPerLevel", 3, 0, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.other.baseCapacity", 6, 1, 200_000),
			parseInt(props, "server.command.rateLimit.actorGroup.other.stepPerLevel", 4, 0, 200_000),
			RedstoneLinkConfig.CurrentLinksPrivacyMode.fromConfigValue(
				props.getProperty("server.currentLinksPrivacy.mode", "masked")
			),
			parseInt(props, "server.currentLinksPrivacy.viewPermissionLevel", 2, 0, 4),
			parseInt(props, "server.currentLinksPrivacy.managePermissionLevel", 2, 0, 4),
			RedstoneLinkConfig.LinkWriteControlMode.fromConfigValue(props.getProperty("server.linkWriteControl.mode", "limited")),
			parseInt(props, "server.linkWriteControl.limited.permissionLevel", 2, 0, 4),
			parseInt(props, "server.linkWriteControl.limited.maxSetSize", 64, 1, 4096),
			parseInt(props, "server.linkWriteControl.protected.permissionLevel", 2, 0, 4),
			parseInt(props, "server.linkWriteControl.protected.managePermissionLevel", 2, 0, 4),
			parseInt(props, "server.command.linkSet.maxInputLength", 1024, 64, 32768),
			parseInt(props, "server.command.activate.batchMaxSerials", 1024, 1, 65536),
			parseInt(props, "server.command.retire.batchMaxSerials", 1024, 1, 65536),
			parseInt(props, "server.command.privacy.currentLinksMask.maxSetSerials", 1024, 1, 65536),
			parseInt(props, "server.command.writeControl.protected.maxSetSerials", 1024, 1, 65536),
			parseInt(props, "server.command.crosschunk.whitelist.maxSetSerials", 1024, 1, 65536),
			parseBoolean(props, "interaction.requireSneakToOpenPairing", true),
			parseBoolean(props, "interaction.requireSneakToOpenLinkerPairing", true),
			parseBoolean(props, "interaction.requireEmptyOffhandToOpenPairing", true)
		);
	}

	/**
	 * 解析整数配置并做区间收敛。
	 */
	static int parseInt(Properties props, String key, int defaultValue, int min, int max) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				RedstoneLinkConfig.logger().warn("Config {}={} is out of range; clamped to [{}..{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			RedstoneLinkConfig.logger().warn("Config {}={} is invalid; falling back to default {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析布尔配置。
	 */
	static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized) || "false".equals(normalized)) {
			return Boolean.parseBoolean(normalized);
		}
		RedstoneLinkConfig.logger().warn("Config {}={} is invalid; falling back to default {}", key, raw, defaultValue);
		return defaultValue;
	}
}
