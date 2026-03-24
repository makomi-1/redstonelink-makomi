package com.makomi.client.config;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 客户端序号外显配置。
 */
public record RedstoneLinkClientOverlayConfig(
	RedstoneLinkClientDisplayConfig.SerialOverlayMode mode,
	int maxDistance,
	float fontScale,
	int nearDistance,
	InputConstants.Key toggleKey,
	boolean farSeeThrough
) {
	/**
	 * @return 是否启用远距离外显
	 */
	public boolean farOverlayEnabled() {
		return mode == RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY
			|| mode == RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_AND_NEAR;
	}

	/**
	 * @return 是否启用近距离外显
	 */
	public boolean nearOverlayEnabled() {
		return mode == RedstoneLinkClientDisplayConfig.SerialOverlayMode.NEAR_ONLY
			|| mode == RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_AND_NEAR;
	}
}
