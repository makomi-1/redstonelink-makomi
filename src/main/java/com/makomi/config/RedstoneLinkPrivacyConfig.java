package com.makomi.config;

/**
 * 当前连接隐私配置。
 */
public record RedstoneLinkPrivacyConfig(
	RedstoneLinkConfig.CurrentLinksPrivacyMode mode,
	int viewPermissionLevel,
	int managePermissionLevel
) {}
