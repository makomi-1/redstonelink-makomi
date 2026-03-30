package com.makomi.client.config;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 客户端显示配置聚合快照。
 */
record RedstoneLinkClientDisplaySnapshot(
	RedstoneLinkClientOverlayConfig overlay,
	RedstoneLinkClientPairingConfig pairing,
	RedstoneLinkClientQuickLinkConfig quickLink
) {
	/**
	 * @return 客户端显示配置默认值
	 */
	static RedstoneLinkClientDisplaySnapshot defaults() {
		return new RedstoneLinkClientDisplaySnapshot(
			new RedstoneLinkClientOverlayConfig(
				RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY,
				24,
				1.0F,
				8,
				InputConstants.getKey("key.keyboard.k"),
				false
			),
			new RedstoneLinkClientPairingConfig(1024),
			new RedstoneLinkClientQuickLinkConfig(InputConstants.getKey("key.keyboard.b"), 1024)
		);
	}
}
