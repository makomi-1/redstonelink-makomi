package com.makomi.config;

import com.makomi.data.LinkNodeType;
import java.util.Map;
import java.util.Set;

/**
 * 跨区块运行时配置快照。
 */
record RedstoneLinkCrossChunkConfigValues(
	int syncSignalTtlTicks,
	boolean syncSignalPersistent,
	boolean syncTargetChunkLoadReplayEnabled,
	boolean activationPulseRelayEnabled,
	int activationPulseTtlTicks,
	boolean activationPulsePersistentExperimental,
	boolean activationToggleRelayEnabled,
	int activationToggleTtlTicks,
	boolean activationTogglePersistentExperimental,
	boolean triggerSourceChunkUnloadInvalidationEnabled,
	boolean triggerSourceInvalidationEnabled,
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
	int retryWarnThreshold,
	int retryErrorThreshold,
	int retryDropThreshold,
	int retryStage1MaxAttempts,
	int retryStage1IntervalTicks,
	int retryStage2MaxAttempts,
	int retryStage2IntervalTicks,
	int retryStage3MaxAttempts,
	int retryStage3IntervalTicks,
	int retryStage4IntervalTicks,
	Set<LinkNodeType> allowedSourceTypes,
	Set<LinkNodeType> allowedTargetTypes,
	Map<String, RedstoneLinkConfig.CrossChunkPreset> presets,
	Map<LinkNodeType, Set<Long>> mergedPresetSources,
	Map<LinkNodeType, Set<Long>> mergedPresetTargets
) {
	/**
	 * @return 跨区块配置默认值
	 */
	static RedstoneLinkCrossChunkConfigValues defaults() {
		return new RedstoneLinkCrossChunkConfigValues(
			40,
			true,
			true,
			false,
			200,
			false,
			false,
			200,
			false,
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
			200,
			1000,
			100,
			99,
			1,
			499,
			5,
			999,
			20,
			100,
			Set.of(LinkNodeType.TRIGGER_SOURCE),
			Set.of(LinkNodeType.CORE),
			Map.of(),
			Map.of(),
			Map.of()
		);
	}
}
