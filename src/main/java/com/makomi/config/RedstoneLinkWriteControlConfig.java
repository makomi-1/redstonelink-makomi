package com.makomi.config;

/**
 * 链接写入控制配置。
 */
public record RedstoneLinkWriteControlConfig(
	RedstoneLinkConfig.LinkWriteControlMode mode,
	int limitedPermissionLevel,
	int limitedMaxSetSize,
	int protectedPermissionLevel,
	int protectedManagePermissionLevel
) {}
