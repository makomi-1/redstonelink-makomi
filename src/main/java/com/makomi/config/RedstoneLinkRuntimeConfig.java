package com.makomi.config;

/**
 * 服务端运行期配置。
 */
public record RedstoneLinkRuntimeConfig(
	boolean coreLoadResyncEnabled,
	boolean triggerSourceLoadResyncEnabled,
	int loadResyncMaxRetry
) {}
