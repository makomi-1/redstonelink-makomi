package com.makomi.config;

/**
 * 网页功能权限配置。
 * <p>
 * 当前单独拆分录制页与 graph 可视化编辑页的服务端功能门禁，
 * 避免继续复用命令权限口径导致两条链路无法独立调节。
 * </p>
 */
public record RedstoneLinkWebConfig(
	int recordingPermissionLevel,
	int graphPermissionLevel
) {}
