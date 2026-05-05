package com.makomi.config;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 跨区块配置解析器。
 * <p>
 * 负责跨区块白名单、preset、重试策略与派发配置的解析。
 * </p>
 */
final class RedstoneLinkCrossChunkConfigParser {
	private RedstoneLinkCrossChunkConfigParser() {
	}

	/**
	 * 解析跨区块配置快照。
	 */
	static RedstoneLinkCrossChunkConfig parse(Properties props) {
		String directBatchingRaw = props == null ? null : props.getProperty("crosschunk.directBatching");
		Set<LinkNodeType> allowedSourceTypes = RedstoneLinkCrossChunkPresetParser.parseTypeSet(
			props,
			"crosschunk.whitelist.sourceTypes",
			Set.of(LinkNodeType.TRIGGER_SOURCE),
			LinkNodeSemantics.Role.SOURCE
		);
		Set<LinkNodeType> allowedTargetTypes = RedstoneLinkCrossChunkPresetParser.parseTypeSet(
			props,
			"crosschunk.whitelist.targetTypes",
			Set.of(LinkNodeType.CORE),
			LinkNodeSemantics.Role.TARGET
		);
		Map<String, RedstoneLinkConfig.CrossChunkPreset> presets = RedstoneLinkCrossChunkPresetParser.parsePresets(
			props,
			allowedSourceTypes,
			allowedTargetTypes
		);
		RedstoneLinkCrossChunkRetryConfig retry = parseRetry(props);
		return new RedstoneLinkCrossChunkConfig(
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.syncSignalTtlTicks", 40, 1, 72_000),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.syncSignalPersistent", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.syncCrossTickOverride", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst", true),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.syncSourceAttachReplay.enabled", false),
			RedstoneLinkConfig.CrossChunkDirectBatchingMode.fromConfigValue(directBatchingRaw),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.dispatch.batchWindowTicks", 0, 0, 2),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.activation.pulse.relay.enabled", false),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.activation.pulse.ttlTicks", 200, 1, 72_000),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.activation.pulse.persistentExperimental", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.activation.toggle.relay.enabled", false),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.activation.toggle.ttlTicks", 200, 1, 72_000),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.activation.toggle.persistentExperimental", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.triggerSourceContextDetachInvalidation.enabled", false),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.queue.enabled", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.queue.defaultTtlTicks", 200, 1, 72_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.queue.maxPendingEntries", 100_000, 1, 2_000_000),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.dispatch.maxPerTick", 500, 1, 20_000),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.forceLoad.enabled", true),
			RedstoneLinkConfig.CrossChunkForceLoadMode.fromConfigValue(props.getProperty("crosschunk.forceLoad.mode", "whitelist")),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.forceLoad.ticketTicks", 80, 1, 7_200),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.forceLoad.maxPerTick", 256, 1, 256),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.forceLoad.maxPerSourcePerTick", 256, 1, 256),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.resident.maxEntries", 128, 1, 256),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.command.enabled", true),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.command.permissionLevel", 2, 0, 4),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.notify.enabled", true),
			RedstoneLinkConfig.CrossChunkNotifyMode.fromConfigValue(props.getProperty("crosschunk.notify.mode", "simple")),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.diag.runtime.enabled", false),
			RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.diag.runtime.warnThresholdMs", 25, 1, 10_000),
			RedstoneLinkConfigParseSupport.parseBoolean(props, "crosschunk.diag.runtime.fanoutCounters.enabled", false),
			allowedSourceTypes,
			allowedTargetTypes,
			presets,
			RedstoneLinkCrossChunkPresetParser.mergePresetBuckets(presets, LinkNodeSemantics.Role.SOURCE),
			RedstoneLinkCrossChunkPresetParser.mergePresetBuckets(presets, LinkNodeSemantics.Role.TARGET),
			retry
		);
	}

	/**
	 * 解析跨区块重试配置。
	 */
	private static RedstoneLinkCrossChunkRetryConfig parseRetry(Properties props) {
		int warnThreshold = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.warnThreshold", 200, 0, 2_000_000);
		int errorThreshold = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.errorThreshold", 1000, 0, 2_000_000);
		if (warnThreshold > 0 && errorThreshold > 0 && errorThreshold < warnThreshold) {
			errorThreshold = warnThreshold;
		}
		int dropThreshold = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.dropThreshold", 2000, 0, 2_000_000);
		if (errorThreshold > 0 && dropThreshold > 0 && dropThreshold < errorThreshold) {
			dropThreshold = errorThreshold;
		}
		int stage1MaxAttempts = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage1.maxAttempts", 99, 1, 2_000_000);
		int stage1IntervalTicks = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage1.intervalTicks", 1, 1, 72_000);
		int stage2MaxAttempts = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage2.maxAttempts", 499, 1, 2_000_000);
		if (stage2MaxAttempts <= stage1MaxAttempts) {
			stage2MaxAttempts = stage1MaxAttempts + 1;
		}
		int stage2IntervalTicks = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage2.intervalTicks", 5, 1, 72_000);
		int stage3MaxAttempts = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage3.maxAttempts", 999, 1, 2_000_000);
		if (stage3MaxAttempts <= stage2MaxAttempts) {
			stage3MaxAttempts = stage2MaxAttempts + 1;
		}
		int stage3IntervalTicks = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage3.intervalTicks", 20, 1, 72_000);
		int stage4IntervalTicks = RedstoneLinkConfigParseSupport.parseInt(props, "crosschunk.retry.stage4.intervalTicks", 100, 1, 72_000);
		return new RedstoneLinkCrossChunkRetryConfig(
			warnThreshold,
			errorThreshold,
			dropThreshold,
			stage1MaxAttempts,
			stage1IntervalTicks,
			stage2MaxAttempts,
			stage2IntervalTicks,
			stage3MaxAttempts,
			stage3IntervalTicks,
			stage4IntervalTicks
		);
	}
}
