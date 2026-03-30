package com.makomi.config;

/**
 * RedstoneLink 基础通用配置。
 */
public record RedstoneLinkGeneralConfig(
	int pulseDurationTicks,
	RedstoneLinkConfig.EmitterEdgeMode emitterEdgeMode,
	int coreOutputPower,
	int maxTargetsPerSetLinks,
	boolean allowOfflineTargetBinding,
	int statePanelRefreshHz,
	int statePanelMaxSubscriptions
) {}
