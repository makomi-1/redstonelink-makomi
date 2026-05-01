package com.makomi.client.config;

/**
 * 客户端显示配置模板生成器。
 */
final class RedstoneLinkClientDisplayTemplate {
	private RedstoneLinkClientDisplayTemplate() {
	}

	/**
	 * 生成客户端配置模板文本。
	 */
	static String buildConfigContent(RedstoneLinkClientDisplaySnapshot snapshot) {
		RedstoneLinkClientOverlayConfig overlay = snapshot.overlay();
		return """
			# RedstoneLink client display config / RedstoneLink 客户端显示配置
			#
			# client.serialOverlayMode
			# zh: 序号外显模式：far(远外显)、near(近外显)、both(远+近)、off(关闭)。
			# en: Serial overlay mode: far, near, both, off.
			client.serialOverlayMode=%s
			
			# client.serialOverlayMaxDistance
			# zh: 远外显最大可见距离（格），范围 4~256。
			# en: Max visible distance (blocks) for serial overlay, range 4~256.
			client.serialOverlayMaxDistance=%s

			# client.serialOverlayFontScale
			# zh: 序号外显字体缩放倍数，范围 0.50~3.00。
			# en: Font scale for serial overlay, range 0.50~3.00.
			client.serialOverlayFontScale=%.2f
			
			# client.serialOverlayToggleKey
			# zh: 序号外显开关按键（推荐使用 key.keyboard.k 这种完整键名，单字母如 K 也可）。
			# en: Toggle key for serial overlay (prefer full key name like key.keyboard.k; single letter like K is also accepted).
			client.serialOverlayToggleKey=%s

			# client.serialOverlayFarSeeThrough
			# zh: 远外显文本是否穿透方块显示（true=穿透，false=被遮挡）。
			# en: Whether far overlay text ignores occlusion (true=see-through, false=occluded).
			client.serialOverlayFarSeeThrough=%s

			# client.smartGlassesFaceVectorToggleKey
			# zh: 智能眼镜定向方向箭头显示开关键（默认需配合 Ctrl 使用，例如 key.keyboard.k 表示 Ctrl+K）。
			# en: Base toggle key for Smart Glasses directional face vectors (used with Ctrl by default, e.g. key.keyboard.k for Ctrl+K).
			client.smartGlassesFaceVectorToggleKey=%s

			# client.smartGlassesFaceVectorEnabled
			# zh: 智能眼镜是否持续显示定向方向箭头（true=显示，false=关闭）。
			# en: Whether Smart Glasses keep directional face vectors visible (true=enabled, false=disabled).
			client.smartGlassesFaceVectorEnabled=%s

			# client.pairingInputMaxLength
			# zh: 配对输入框最大输入长度（字符），范围 64~32768，默认 1024。
			# en: Maximum input length (chars) for pairing textbox, range 64~32768, default 1024.
			client.pairingInputMaxLength=%s

			# client.quickLinkModeToggleKey
			# zh: 快速连接工具模式切换按键（默认 B；用于在序号/频道缓存模式间切换）。
			# en: Quick Link mode toggle key (default B; switches between serial and channel cache modes).
			client.quickLinkModeToggleKey=%s

			# client.quickLinkSerialCacheMaxLength
			# zh: 快速连接工具序号缓存输入最大长度（字符），范围 64~32768，默认 1024。
			# en: Maximum serial-cache input length (chars) for Quick Link Tool, range 64~32768, default 1024.
			client.quickLinkSerialCacheMaxLength=%s
			""".formatted(
				overlay.mode().configToken(),
				overlay.maxDistance(),
				overlay.fontScale(),
				overlay.toggleKey().getName(),
				Boolean.toString(overlay.farSeeThrough()),
				overlay.faceVectorToggleKey().getName(),
				Boolean.toString(overlay.faceVectorEnabled()),
				snapshot.pairing().inputMaxLength(),
				snapshot.quickLink().modeToggleKey().getName(),
				snapshot.quickLink().serialCacheMaxLength()
			);
	}
}
