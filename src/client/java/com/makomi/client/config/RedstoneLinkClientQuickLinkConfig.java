package com.makomi.client.config;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 客户端快速连接工具配置。
 */
public record RedstoneLinkClientQuickLinkConfig(InputConstants.Key modeToggleKey, int serialCacheMaxLength) {}
