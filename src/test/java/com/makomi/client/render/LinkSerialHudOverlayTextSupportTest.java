package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.CrossChunkNodeIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 近外显文案映射契约测试。
 */
@Tag("stable-core")
class LinkSerialHudOverlayTextSupportTest {
	/**
	 * 跨区块身份应稳定映射到对应翻译键。
	 */
	@Test
	void resolveCrossChunkIdentityMessageKeyShouldMapAllStates() {
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_normal",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.NORMAL)
		);
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_force_load",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.FORCE_LOAD)
		);
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_resident",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.RESIDENT)
		);
	}

	/**
	 * 空身份应回退到普通身份翻译键。
	 */
	@Test
	void resolveCrossChunkIdentityMessageKeyShouldFallbackToNormalWhenNull() {
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_normal",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(null)
		);
	}
}
