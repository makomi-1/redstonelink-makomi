package com.makomi.config;

/**
 * RedstoneLink 命令相关配置。
 */
public record RedstoneLinkCommandConfig(
	int permissionLevel,
	int otherPermissionLevel,
	boolean benchmarkModeEnabled,
	boolean inputEnabled,
	boolean nodeTraceEnabled,
	int linkSetMaxInputLength,
	int activateBatchMaxSerials,
	int retireBatchMaxSerials,
	int currentLinksMaskSetMaxSerials,
	int writeControlProtectedSetMaxSerials,
	int crossChunkWhitelistSetMaxSerials
) {}
