package com.makomi.config;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 跨区块配置。
 */
public record RedstoneLinkCrossChunkConfig(
	int syncSignalTtlTicks,
	boolean syncSignalPersistent,
	boolean syncTargetChunkLoadReplayEnabled,
	boolean syncTargetChunkLoadReplayImmediateAttemptFirst,
	boolean syncSourceAttachReplayEnabled,
	RedstoneLinkConfig.CrossChunkDirectSyncBatchingMode directSyncBatchingMode,
	int dispatchBatchWindowTicks,
	boolean activationPulseRelayEnabled,
	int activationPulseTtlTicks,
	boolean activationPulsePersistentExperimental,
	boolean activationToggleRelayEnabled,
	int activationToggleTtlTicks,
	boolean activationTogglePersistentExperimental,
	boolean triggerSourceContextDetachInvalidationEnabled,
	boolean queueEnabled,
	int queueDefaultTtlTicks,
	int dispatchMaxPerTick,
	boolean forceLoadEnabled,
	RedstoneLinkConfig.CrossChunkForceLoadMode forceLoadMode,
	int forceLoadTicketTicks,
	int forceLoadMaxPerTick,
	int forceLoadMaxPerSourcePerTick,
	boolean commandEnabled,
	int commandPermissionLevel,
	boolean notifyEnabled,
	RedstoneLinkConfig.CrossChunkNotifyMode notifyMode,
	boolean runtimeDiagEnabled,
	int runtimeDiagWarnThresholdMs,
	boolean runtimeDiagFanoutCountersEnabled,
	Set<LinkNodeType> allowedSourceTypes,
	Set<LinkNodeType> allowedTargetTypes,
	Map<String, RedstoneLinkConfig.CrossChunkPreset> presets,
	Map<LinkNodeType, Set<Long>> mergedPresetSources,
	Map<LinkNodeType, Set<Long>> mergedPresetTargets,
	RedstoneLinkCrossChunkRetryConfig retry
) {
	/**
	 * @return 配置中的只读 preset 名称列表
	 */
	public List<String> presetNames() {
		return presets.keySet().stream().sorted().toList();
	}

	/**
	 * 读取指定名称的只读 preset。
	 */
	public Optional<RedstoneLinkConfig.CrossChunkPreset> preset(String presetName) {
		if (presetName == null) {
			return Optional.empty();
		}
		String normalized = presetName.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(presets.get(normalized));
	}

	/**
	 * 判断给定类型+序号是否命中预设白名单。
	 */
	public boolean presetContains(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Map<LinkNodeType, Set<Long>> mergedBucket = role == LinkNodeSemantics.Role.SOURCE
			? mergedPresetSources
			: mergedPresetTargets;
		Set<Long> serials = mergedBucket.get(type);
		return serials != null && serials.contains(serial);
	}
}
